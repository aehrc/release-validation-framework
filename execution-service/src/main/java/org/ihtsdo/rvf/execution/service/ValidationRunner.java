package org.ihtsdo.rvf.execution.service;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Calendar;

import net.rcarz.jiraclient.JiraException;
import org.apache.commons.lang.StringUtils;
import org.ihtsdo.rvf.entity.ValidationReport;
import org.ihtsdo.rvf.execution.service.ValidationReportService.State;
import org.ihtsdo.rvf.execution.service.config.MysqlExecutionConfig;
import org.ihtsdo.rvf.execution.service.config.ValidationRunConfig;
import org.ihtsdo.rvf.jira.JiraService;
import org.ihtsdo.rvf.validation.StructuralTestRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Service;

@Service
@Scope("prototype")
public class ValidationRunner {	
	@Autowired
	private StructuralTestRunner structuralTestRunner;
	
	@Autowired
	private ValidationReportService reportService;
	
	@Autowired
	private ValidationVersionLoader releaseVersionLoader;
	
	@Autowired 
	private DroolsRulesValidationService droolsValidationService;
	
	@Autowired
	private MysqlValidationService mysqlValidationService;


	@Autowired
	private MRCMValidationService mrcmValidationService;

	@Autowired
	private JiraService jiraService;
	
	private Logger logger = LoggerFactory.getLogger(ValidationRunner.class);

	public void run(ValidationRunConfig validationConfig) {
		try {
			runValidations(validationConfig);
		} catch (final Throwable t) {
			StringWriter errors = new StringWriter();
			t.printStackTrace(new PrintWriter(errors));
			String failureMsg = "System Failure: " + t.getMessage() + " : " + errors.toString();
			ValidationStatusReport statusReport = new ValidationStatusReport(validationConfig);
			statusReport.addFailureMessage(failureMsg);
			logger.error("Exception thrown, writing as result",t);
			try {
				reportService.writeResults(statusReport, State.FAILED, validationConfig.getStorageLocation());
			} catch (final Exception e) {
				logger.error("Failed to record failure (which was: " + failureMsg + ") due to " + e.getMessage());
			}
		}
	}
	
	private void runValidations(ValidationRunConfig validationConfig) throws Exception {
		Calendar startTime = Calendar.getInstance();
		MysqlExecutionConfig executionConfig = releaseVersionLoader.createExecutionConfig(validationConfig);
		releaseVersionLoader.downloadProspectiveFiles(validationConfig);
		if (validationConfig.getLocalProspectiveFile() == null) {
			reportService.writeState(State.FAILED, validationConfig.getStorageLocation());
			String errorMsg ="Prospective file can't be null " + validationConfig.getLocalProspectiveFile();
			reportService.writeProgress(errorMsg, validationConfig.getStorageLocation());
			logger.error(errorMsg);
		}
		ValidationReport report = new ValidationReport();
		report.setExecutionId(executionConfig.getExecutionId());
		report.setReportUrl(validationConfig.getUrl());
		ValidationStatusReport statusReport = new ValidationStatusReport(validationConfig);
		statusReport.setResultReport(report);
		runRF2StructureTests(validationConfig, statusReport);
		
		mysqlValidationService.runRF2MysqlValidations(validationConfig, statusReport);
		if (validationConfig.isEnableDrools()) {
			// Run Drools validations
			String droolsTestStartMsg = "Start drools validation for release file:" + validationConfig.getTestFileName();
			logger.info(droolsTestStartMsg);
			reportService.writeProgress(droolsTestStartMsg, validationConfig.getStorageLocation());
			droolsValidationService.runDroolsAssertions(statusReport, validationConfig);
		}
		if(validationConfig.isEnableMRCMValidation()) {
			// Run MRCM validations
			String mrcmTestStartMsg = "Start MRCM validation for release file: " + validationConfig.getTestFileName();
			logger.info(mrcmTestStartMsg);
			reportService.writeProgress(mrcmTestStartMsg, validationConfig.getStorageLocation());
			mrcmValidationService.runMRCMAssertionTests(statusReport, validationConfig, executionConfig.getEffectiveTime(), executionConfig.getExecutionId());
		}
		report.sortAssertionLists();

		// Create Jira link for failed assertions
		if(validationConfig.isJiraIssueCreationFlag()) {
			addJiraLinkToReport(validationConfig, report, validationConfig.getEffectiveTime());
		}
		
		final Calendar endTime = Calendar.getInstance();
		final long timeTaken = (endTime.getTimeInMillis() - startTime.getTimeInMillis()) / 60000;
		logger.info(String.format("Finished execution with runId : [%1s] in [%2s] minutes ", validationConfig.getRunId(), timeTaken));
		statusReport.setStartTime(startTime.getTime());
		statusReport.setEndTime(endTime.getTime());
		State state = statusReport.getFailureMessages().isEmpty() ? State.COMPLETE : State.FAILED;
		reportService.writeResults(statusReport, state, validationConfig.getStorageLocation());
	}
	
	private void runRF2StructureTests(ValidationRunConfig validationConfig, ValidationStatusReport statusReport) throws Exception{
		logger.info(String.format("Started execution with runId [%1s] : ", validationConfig.getRunId()));
		// load the filename
		String structureTestStartMsg = "Start structure testing for release file:" + validationConfig.getTestFileName();
		logger.info(structureTestStartMsg);
		String reportStorage = validationConfig.getStorageLocation();
		reportService.writeProgress(structureTestStartMsg, reportStorage);
		reportService.writeState(State.RUNNING, reportStorage);

		boolean isFailed = structuralTestRunner.verifyZipFileStructure(statusReport.getResultReport(), 
																		validationConfig.getLocalProspectiveFile(),
																		validationConfig.getRunId(),
																		validationConfig.getLocalManifestFile(),
																		validationConfig.isWriteSucceses(),
																		validationConfig.getUrl(),
																		validationConfig.getStorageLocation(),
																		validationConfig.getFailureExportMax());
		
		reportService.putFileIntoS3(reportStorage, new File(structuralTestRunner.getStructureTestReportFullPath()));
		if (isFailed) {
			reportService.writeResults(statusReport, State.FAILED, reportStorage);
		}
	}

	private void addJiraLinkToReport(ValidationRunConfig validationRunConfig, ValidationReport report, String effectiveTime) {
		if(validationRunConfig.isJiraIssueCreationFlag()) {
			String reportingStage = validationRunConfig.getReportingStage();
			if(StringUtils.isBlank(reportingStage)) {
				logger.error("Reporting stage is required for creating JIRA ticket");
				return;
			}
			String productName = validationRunConfig.getProductName();
			if(StringUtils.isBlank(productName)) {
				logger.error("Product name is required for creating JIRA ticket");
				return;
			}
			if(StringUtils.isBlank(effectiveTime)) {
				logger.error("Effective time is required for creating JIRA ticket");
				return;
			}
			// Add Jira ticket for each fail assertions
			try {
				jiraService.addJiraTickets(validationRunConfig.getProductName(),effectiveTime,validationRunConfig.getReportingStage(), report.getAssertionsFailed());
			} catch (JiraException e) {
				logger.error("Error while creating Jira Ticket for failed assertions. Message : " + e.getMessage());
			}
		}
	}
}
