package org.ihtsdo.rvf.core.service;

import org.ihtsdo.rvf.core.data.model.ValidationReport;
import org.ihtsdo.rvf.core.service.config.ValidationRunConfig;
import org.ihtsdo.rvf.core.service.pojo.ValidationStatusReport;
import org.ihtsdo.rvf.core.service.structure.validation.StructuralTestRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Structure testing must not block the other phases.
 *
 * <p>A wall-clock assertion would be flaky, so this pins the property directly:
 * the SQL phase is held on a latch that only structure testing can release. If
 * the two are sequential the run deadlocks and the test times out; if they are
 * concurrent both complete. That fails on the previous arrangement for the right
 * reason rather than because a machine was briefly slow.
 */
class ValidationRunnerPhaseConcurrencyTest {

	private ValidationRunner runner;
	private StructuralTestRunner structuralTestRunner;
	private SqlAssertionValidationService sqlService;
	private ValidationReportService reportService;

	/**
	 * Released when the SQL phase begins. Structure testing waits on it, so the
	 * wait can only be satisfied by the two running at the same time - the
	 * direction that matters. Latching the other way round proves nothing,
	 * because sequential structure testing runs first and releases the latch
	 * before anything is waiting on it.
	 */
	private final CountDownLatch sqlPhaseStarted = new CountDownLatch(1);
	private final AtomicBoolean structuralSawSqlRunning = new AtomicBoolean(false);

	@BeforeEach
	void setUp() throws Exception {
		runner = new ValidationRunner();
		structuralTestRunner = mock(StructuralTestRunner.class);
		sqlService = mock(SqlAssertionValidationService.class);
		reportService = mock(ValidationReportService.class);

		when(structuralTestRunner.getStructureTestReportFullPath())
				.thenReturn(System.getProperty("java.io.tmpdir") + "/structure-report-that-need-not-exist.txt");

		// Structure testing announces itself and returns "no failures".
		when(structuralTestRunner.verifyZipFileStructure(any(), nullable(java.io.File.class),
				nullable(java.io.File.class), anyLong(), anyBoolean(),
				nullable(java.io.File.class), anyBoolean(), nullable(String.class),
				anyString(), nullable(Integer.class)))
				.thenAnswer(invocation -> {
					structuralSawSqlRunning.set(sqlPhaseStarted.await(10, TimeUnit.SECONDS));
					return false;
				});

		// Announces that the SQL phase has begun. If structure testing has to
		// finish first, this never runs while structure testing is waiting.
		when(sqlService.runRF2Validations(any(), any())).thenAnswer(invocation -> {
			sqlPhaseStarted.countDown();
			return invocation.getArgument(1);
		});

		ReflectionTestUtils.setField(runner, "structuralTestRunner", structuralTestRunner);
		ReflectionTestUtils.setField(runner, "sqlAssertionValidationService", sqlService);
		ReflectionTestUtils.setField(runner, "reportService", reportService);
	}

	private ValidationRunConfig config() {
		ValidationRunConfig config = new ValidationRunConfig();
		config.addRunId(1788310384720L);
		config.addStorageLocation("phase-concurrency/");
		config.addGroupsList(List.of("file-centric-validation"));
		config.setTestFileName("amtv4.zip");
		return config;
	}

	@Test
	void structureTestingRunsAlongsideTheAssertions() throws Exception {
		ValidationStatusReport statusReport = new ValidationStatusReport(config());
		statusReport.setResultReport(new ValidationReport());

		ReflectionTestUtils.invokeMethod(runner, "doRunValidations", config(), statusReport);

		assertTrue(structuralSawSqlRunning.get(),
				"structure testing ran to completion without the SQL phase ever "
				+ "starting, so the phases are still sequential");
	}

	/**
	 * Concurrency must not cost findings. Structure testing writes into its own
	 * report now, and that report has to reach the merged one - otherwise every
	 * structural failure would silently vanish, which is a far worse outcome than
	 * the run being slow.
	 */
	@Test
	void structuralFindingsSurviveTheMerge() throws Exception {
		when(structuralTestRunner.verifyZipFileStructure(any(), nullable(java.io.File.class),
				nullable(java.io.File.class), anyLong(), anyBoolean(),
				nullable(java.io.File.class), anyBoolean(), nullable(String.class),
				anyString(), nullable(Integer.class)))
				.thenAnswer(invocation -> {
					ValidationReport report = invocation.getArgument(0);
					report.setTotalTestsRun(7);
					report.setTotalFailures(2);
					return true;
				});

		ValidationStatusReport statusReport = new ValidationStatusReport(config());
		statusReport.setResultReport(new ValidationReport());

		ReflectionTestUtils.invokeMethod(runner, "doRunValidations", config(), statusReport);

		assertEquals(7, statusReport.getResultReport().getTotalTestsRun(),
				"structural counts must be merged into the run's report");
		assertEquals(2, statusReport.getResultReport().getTotalFailures());
	}
}
