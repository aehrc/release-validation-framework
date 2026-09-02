package org.ihtsdo.rvf.core.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.jms.JMSException;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.io.FileUtils;
import org.ihtsdo.otf.jms.MessagingHelper;
import org.ihtsdo.otf.rest.exception.BusinessServiceException;
import org.ihtsdo.rvf.core.data.model.TestRunItem;
import org.ihtsdo.rvf.core.data.model.TestType;
import org.ihtsdo.rvf.core.data.model.ValidationReport;
import org.ihtsdo.rvf.core.service.ValidationReportService.State;
import org.ihtsdo.rvf.core.service.config.ValidationRunConfig;
import org.ihtsdo.rvf.core.service.pojo.ValidationStatusReport;
import org.ihtsdo.rvf.core.service.pojo.ValidationStatusResponse;
import org.ihtsdo.rvf.core.service.structure.validation.StructuralTestRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Orchestrates the five passes of a validation, in EITHER engine.
 *
 * <p>It used to be {@code @ConditionalOnMysqlEngine}, and the reason was one
 * line: the SQL-assertion pass named {@code MysqlValidationService} directly.
 * The other four - structural, Drools, MRCM, traceability - already work off RF2
 * files and touch no datasource. So a single concrete reference made the whole
 * orchestrator absent under {@code rvf.execution.engine=duckdb}, and with it the
 * only consumer of the validation queue: a submitted run was accepted, enqueued,
 * and never picked up.
 *
 * <p>That line now takes {@link SqlAssertionValidationService}, of which exactly
 * one implementation is registered per engine.
 */
@Service
public class ValidationRunner {

	public static final List<String> EMPTY_TEST_ASSERTION_GROUPS = Collections.singletonList("empty-test");

	@Autowired
	private StructuralTestRunner structuralTestRunner;
	
	@Autowired
	private ValidationReportService reportService;
	
	// The acquisition half, which carries no engine condition. This used to be
	// ValidationVersionLoader; that class is still MySQL-only because its other
	// half loads into a schema, and injecting it here would have kept this
	// orchestrator MySQL-only with it.
	@Autowired
	private ReleaseAcquisitionService releaseAcquisitionService;
	
	@Autowired 
	private DroolsRulesValidationService droolsValidationService;
	
	@Autowired
	private SqlAssertionValidationService sqlAssertionValidationService;

	@Autowired
	private MRCMValidationService mrcmValidationService;

	@Autowired
	private TraceabilityComparisonService traceabilityComparisonService;

	@Autowired
	private MessagingHelper messagingHelper;

	private static final String MSG_VALIDATIONS_RUN = "Validations executed. Failures count: ";
	private static final String MSG_VALIDATIONS_DISABLED = "Validations are disabled.";

	private final Logger logger = LoggerFactory.getLogger(ValidationRunner.class);

	public void run(ValidationRunConfig validationConfig) {
		try {
			runValidations(validationConfig);
		} catch (final Exception t) {
			StringWriter errors = new StringWriter();
			t.printStackTrace(new PrintWriter(errors));
			String failureMsg = "System Failure: " + t.getMessage() + " : " + errors;
			ValidationStatusReport statusReport = new ValidationStatusReport(validationConfig);
			statusReport.addFailureMessage(failureMsg);
			logger.error("Exception thrown, writing as result",t);
			try {
				updateRvfState(validationConfig, State.FAILED);
				reportService.writeResults(statusReport, State.FAILED, validationConfig.getStorageLocation());
			} catch (final Exception e) {
				throw new IllegalStateException("Failed to record failure (which was: " + failureMsg + ")", e);
			}
		}
	}
	
	private void runValidations(ValidationRunConfig validationConfig) throws Exception {
		try {
			// Prepare to run validations
			Calendar startTime = Calendar.getInstance();
			releaseAcquisitionService.downloadProspectiveFiles(validationConfig);
			releaseAcquisitionService.downloadPreviousRelease(validationConfig);
			releaseAcquisitionService.downloadDependencyReleases(validationConfig);

			if (validationConfig.getLocalProspectiveFile() == null) {
				reportService.writeState(State.FAILED, validationConfig.getStorageLocation());
				String errorMsg = "Prospective file can't be null " + validationConfig.getLocalProspectiveFile();
				reportService.writeProgress(errorMsg, validationConfig.getStorageLocation());
				logger.error(errorMsg);
			}
			ValidationReport report = new ValidationReport();
			report.setExecutionId(validationConfig.getRunId());
			report.setReportUrl(validationConfig.getUrl());
			ValidationStatusReport statusReport = new ValidationStatusReport(validationConfig);
			statusReport.setResultReport(report);

			if (!EMPTY_TEST_ASSERTION_GROUPS.equals(validationConfig.getGroupsList())) {
				// Actually run validations
				doRunValidations(validationConfig, statusReport);
			}

			// Update reports and status after validations run
			report.sortAssertionLists();
			final Calendar endTime = Calendar.getInstance();
			final long timeTaken = (endTime.getTimeInMillis() - startTime.getTimeInMillis()) / 60000;
			logger.info("Finished execution with runId : {} in {} minutes ", validationConfig.getRunId(), timeTaken);
			statusReport.setStartTime(startTime.getTime());
			statusReport.setEndTime(endTime.getTime());
			report.setTimeTakenInSeconds(timeTaken*60);
			State state = statusReport.getFailureMessages().isEmpty() ? State.COMPLETE : State.FAILED;
			updateRvfState(validationConfig, state);
			updateExecutionSummary(statusReport, validationConfig);

			reportService.writeResults(statusReport, state, validationConfig.getStorageLocation());
		} finally {
			// Clean up release package file
			if (validationConfig.getLocalProspectiveFile() != null) {
				FileUtils.deleteQuietly(validationConfig.getLocalProspectiveFile());
			}
			if (validationConfig.getLocalManifestFile() != null) {
				FileUtils.deleteQuietly(validationConfig.getLocalManifestFile());
			}
			if (validationConfig.getLocalReleaseFiles() != null) {
				validationConfig.getLocalReleaseFiles().forEach(FileUtils::deleteQuietly);
			}
		}
	}

	/**
	 * Every phase runs concurrently, so a run costs the slowest phase rather than
	 * the sum of them.
	 *
	 * <p>Structure testing used to run to completion before the rest started, and
	 * that ordering was not protecting anything: {@link #runRF2StructureTests}
	 * writes an interim FAILED report when the archive is malformed and then
	 * <em>falls through</em> - it has never returned early or thrown, so the
	 * assertions always ran against a failed archive anyway. Nothing downstream
	 * reads its outcome; the final state is computed from
	 * {@code failureMessages} once every phase has been merged.
	 *
	 * <p>It therefore gets the same treatment as the other phases: its own
	 * {@link ValidationStatusReport}, merged at the end. That part is
	 * <b>required</b>, not tidiness - it previously wrote its findings straight
	 * into the shared report, which is the same object
	 * {@code mergeValidationStatusReports} mutates as each other phase finishes.
	 * Left shared, the merges and the structural writes would race on the same
	 * assertion lists.
	 *
	 * <p>The phases contend, so the saving is less than the phase's isolated
	 * cost: structural is CPU-bound over the same files Drools is reading. It
	 * still comes off the critical path, which is what matters once Drools is
	 * fast enough that structural is a visible fraction of the run.
	 */
	private void doRunValidations(ValidationRunConfig validationConfig, ValidationStatusReport statusReport) throws Exception {
		Map<String, Future<ValidationStatusReport>> taskMap = new HashMap<>();
		try (ExecutorService executorService = Executors.newFixedThreadPool(5)) {
			StringBuilder statusMessages = new StringBuilder();

			ValidationStatusReport structuralStatusReport = new ValidationStatusReport(validationConfig);
			structuralStatusReport.setResultReport(new ValidationReport());
			taskMap.put("Structure Tests", executorService.submit(() -> {
				runRF2StructureTests(validationConfig, structuralStatusReport);
				return structuralStatusReport;
			}));

			if (!CollectionUtils.isEmpty(validationConfig.getGroupsList())) {
				statusMessages.append("RVF assertions validation started");
				reportService.writeProgress(statusMessages.toString(), validationConfig.getStorageLocation());
				ValidationStatusReport sqlValidationStatusReport = new ValidationStatusReport(validationConfig);
				sqlValidationStatusReport.setResultReport(new ValidationReport());
				taskMap.put("SQL Assertions", executorService.submit(() -> sqlAssertionValidationService.runRF2Validations(validationConfig, sqlValidationStatusReport)));
			}

			if (validationConfig.isEnableDrools()) {
				statusMessages.append(statusMessages.isEmpty() ? "" : "\n").append("Drools rules validation started");
				reportService.writeProgress(statusMessages.toString(), validationConfig.getStorageLocation());
				ValidationStatusReport droolsValidationStatusReport = new ValidationStatusReport(validationConfig);
				droolsValidationStatusReport.setResultReport(new ValidationReport());
				taskMap.put("Drools Assertions", executorService.submit(() -> droolsValidationService.runDroolsAssertions(validationConfig, droolsValidationStatusReport)));
			}

			if (validationConfig.isEnableMRCMValidation()) {
				statusMessages.append(statusMessages.isEmpty() ? "" : "\n").append("MRCM validation started");
				reportService.writeProgress(statusMessages.toString(), validationConfig.getStorageLocation());
				ValidationStatusReport mrcmValidationStatusReport = new ValidationStatusReport(validationConfig);
				mrcmValidationStatusReport.setResultReport(new ValidationReport());
				taskMap.put("MRCM Validation", executorService.submit(() -> mrcmValidationService.runMRCMAssertionTests(mrcmValidationStatusReport, validationConfig)));
			}

			if (validationConfig.isEnableTraceabilityValidation()) {
				statusMessages.append(statusMessages.isEmpty() ? "" : "\n").append("Traceability comparison validation started");
				reportService.writeProgress(statusMessages.toString(), validationConfig.getStorageLocation());
				ValidationStatusReport traceabilityComparisonReport = new ValidationStatusReport(validationConfig);
				traceabilityComparisonReport.setResultReport(new ValidationReport());
				taskMap.put("Traceability Comparison", executorService.submit(() -> {
					traceabilityComparisonService.runTraceabilityComparison(traceabilityComparisonReport, validationConfig);
					return traceabilityComparisonReport;
				}));
			}

			for (Map.Entry<String, Future<ValidationStatusReport>> entry : taskMap.entrySet()) {
				mergeValidationStatusReports(statusReport, entry.getValue().get());
			}
		}
	}

	private void updateRvfState(final ValidationRunConfig config, final State state) throws JsonProcessingException, JMSException {
		final String responseQueue = config.getResponseQueue();
		if (responseQueue != null) {
			logger.info("Updating RVF state to {}: {}", state, responseQueue);
			messagingHelper.send(responseQueue, new ValidationStatusResponse(config, state));
		}
	}

	private void mergeValidationStatusReports(ValidationStatusReport mainValidationReport, ValidationStatusReport validationTaskReport) {
		ValidationReport mainResult = mainValidationReport.getResultReport();
		ValidationReport taskResult = validationTaskReport.getResultReport();

		mainResult.getAssertionsFailed().addAll(taskResult.getAssertionsFailed());
		mainResult.getAssertionsWarning().addAll(taskResult.getAssertionsWarning());
		mainResult.getAssertionsSkipped().addAll(taskResult.getAssertionsSkipped());
		mainResult.getAssertionsPassed().addAll(taskResult.getAssertionsPassed());
		
		mainResult.setTotalTestsRun(mainResult.getTotalTestsRun() + taskResult.getTotalTestsRun());
		mainResult.setTotalFailures(mainResult.getTotalFailures() + taskResult.getTotalFailures());
		mainResult.setTotalWarnings(mainResult.getTotalWarnings() + taskResult.getTotalWarnings());
		mainResult.setTotalTestsIncomplete(mainResult.getTotalTestsIncomplete() + taskResult.getTotalTestsIncomplete());
		mainResult.setTotalSkips(mainResult.getTotalSkips() + taskResult.getTotalSkips());

		mainValidationReport.getFailureMessages().addAll(validationTaskReport.getFailureMessages());
		mainValidationReport.getRf2FilesLoaded().addAll(validationTaskReport.getRf2FilesLoaded());
		mainValidationReport.setTotalRF2FilesLoaded(mainValidationReport.getTotalRF2FilesLoaded());

		mainValidationReport.getReportSummary().putAll(validationTaskReport.getReportSummary());
		
	}
	
	private void runRF2StructureTests(ValidationRunConfig validationConfig, ValidationStatusReport statusReport) throws NoSuchAlgorithmException, IOException, DecoderException, BusinessServiceException {
		logger.info("Started execution with runId {}", validationConfig.getRunId());
		// load the filename
		String structureTestStartMsg = "Start structure testing for release file:" + validationConfig.getTestFileName();
		logger.info(structureTestStartMsg);
		String reportStorage = validationConfig.getStorageLocation();
		reportService.writeProgress(structureTestStartMsg, reportStorage);
		reportService.writeState(State.RUNNING, reportStorage);
		File localPrevousReleaseFile = validationConfig.getLocalReleaseFiles() != null ? validationConfig.getLocalReleaseFiles().stream().filter(file -> file.getName().equals(validationConfig.getPreviousRelease())).findFirst().orElse(null) : null;
		boolean isFailed = structuralTestRunner.verifyZipFileStructure(statusReport.getResultReport(),
																		validationConfig.getLocalProspectiveFile(),
																		localPrevousReleaseFile,
																		validationConfig.getRunId(),
																		validationConfig.isRf2DeltaOnly(),
																		validationConfig.getLocalManifestFile(),
																		validationConfig.isWriteSuccess(),
																		validationConfig.getUrl(),
																		validationConfig.getStorageLocation(),
																		validationConfig.getFailureExportMax());
		
		reportService.putFileIntoS3(reportStorage, new File(structuralTestRunner.getStructureTestReportFullPath()));
		if (isFailed) {
			reportService.writeResults(statusReport, State.FAILED, reportStorage);
		}
	}

	private void updateExecutionSummary(ValidationStatusReport statusReport, ValidationRunConfig validationRunConfig) {
		List<TestRunItem> failures = statusReport.getResultReport().getAssertionsFailed();
		Map<TestType, Integer> testTypeFailuresCount = new EnumMap<>(TestType.class);
		testTypeFailuresCount.put(TestType.ARCHIVE_STRUCTURAL, 0);
		testTypeFailuresCount.put(TestType.SQL, !CollectionUtils.isEmpty(validationRunConfig.getGroupsList()) ? 0 : -1);
		testTypeFailuresCount.put(TestType.DROOL_RULES, validationRunConfig.isEnableDrools() ? 0 : -1);
		testTypeFailuresCount.put(TestType.MRCM, validationRunConfig.isEnableMRCMValidation() ? 0 : -1);
		testTypeFailuresCount.put(TestType.TRACEABILITY, validationRunConfig.isEnableTraceabilityValidation() ? 0 : -1);
		for (TestRunItem failure : failures) {
			TestType testType = failure.getTestType();
			testTypeFailuresCount.put(testType, testTypeFailuresCount.get(testType)+1);
		}
		for (Map.Entry<TestType, Integer> entry : testTypeFailuresCount.entrySet()) {
			if(statusReport.getReportSummary().get(entry.getKey().name()) == null) {
				Integer failuresCount = entry.getValue();
				statusReport.getReportSummary().put(entry.getKey().name(), failuresCount >= 0 ? MSG_VALIDATIONS_RUN + failuresCount : MSG_VALIDATIONS_DISABLED);
			}
		}
	}
}
