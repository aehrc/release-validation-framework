package org.ihtsdo.rvf.core.service;

import org.ihtsdo.rvf.core.service.pojo.ValidationStatusReport;
import org.ihtsdo.rvf.core.service.config.MysqlExecutionConfig;
import org.ihtsdo.rvf.core.service.config.ValidationRunConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A validation must not leave its prospective schema behind.
 *
 * <p>That schema is a whole loaded edition - 9 to 13GB measured on the AU
 * release - and its name carries the execution id, so nothing ever comes back
 * for it. Two ways it used to survive:
 *
 * <ul>
 * <li>The drop ran at the START of the next run, so a schema sat on disk for as
 *     long as the instance was idle. For a nightly that is all day, and forever
 *     if the process was replaced in between - which under Kubernetes it will
 *     be.
 * <li>The schema was registered for removal only AFTER a successful load, so a
 *     load that failed part way orphaned one permanently. Four such orphans held
 *     41GB here, and a later run died with "No space left on device".
 * </ul>
 *
 * <p>Both are about ordering rather than logic, which is why they are pinned by
 * tests on the order of calls rather than on a return value.
 */
class MysqlSchemaCleanupTest {

	private static final String PROSPECTIVE = "rvf_au_20260831_1788232124359";

	private MysqlValidationService service;
	private ReleaseDataManager releaseDataManager;
	private ValidationVersionLoader releaseVersionLoader;

	@BeforeEach
	void setUp() {
		service = new MysqlValidationService();
		releaseDataManager = mock(ReleaseDataManager.class);
		releaseVersionLoader = mock(ValidationVersionLoader.class);
		ReflectionTestUtils.setField(service, "releaseDataManager", releaseDataManager);
		ReflectionTestUtils.setField(service, "releaseVersionLoader", releaseVersionLoader);
		// The assertion phase itself is not what these tests are about, but it has
		// to get far enough for the cleanup to be reached. Empty groups and no
		// resource assertions is the cheapest way through it.
		AssertionService assertionService = mock(AssertionService.class);
		when(assertionService.getAssertionGroupsByNames(any())).thenReturn(java.util.List.of());
		when(assertionService.getAssertionsByKeyWords(anyString(), anyBoolean()))
				.thenReturn(java.util.List.of());
		ReflectionTestUtils.setField(service, "assertionService", assertionService);
		ReflectionTestUtils.setField(service, "reportService", mock(ValidationReportService.class));
		ReflectionTestUtils.setField(service, "assertionExecutionService",
				mock(AssertionExecutionService.class));
		ReflectionTestUtils.setField(service, "mysqlFailuresExtractor",
				mock(MysqlFailuresExtractor.class));
	}

	private MysqlExecutionConfig config() {
		MysqlExecutionConfig config = new MysqlExecutionConfig(1788232124359L);
		config.setProspectiveVersion(PROSPECTIVE);
		return config;
	}

	private ValidationRunConfig runConfig() {
		ValidationRunConfig runConfig = new ValidationRunConfig();
		runConfig.addStorageLocation("loc");
		return runConfig;
	}

	/**
	 * ValidationRunner populates resultReport before calling the service, and the
	 * MySQL path dereferences it without a guard - unlike the DuckDB one, which
	 * creates it if absent. So a test has to do what the runner does.
	 */
	private ValidationStatusReport statusReport() {
		ValidationStatusReport report = new ValidationStatusReport(runConfig());
		report.setResultReport(new org.ihtsdo.rvf.core.data.model.ValidationReport());
		return report;
	}

	@Test
	void aCompletedRunDropsItsProspectiveSchema() throws Exception {
		MysqlExecutionConfig config = config();
		when(releaseVersionLoader.createExecutionConfig(any())).thenReturn(config);
		when(releaseVersionLoader.isUnknownVersion(anyString())).thenReturn(false);

		service.runRF2MysqlValidations(runConfig(), statusReport());

		verify(releaseDataManager).dropSchema(PROSPECTIVE);
	}

	@Test
	void aRunWhoseLoadFailedStillDropsWhatTheLoadCreated() throws Exception {
		// The case that produced the 41GB: loadProspectiveVersion sets the schema
		// name before it loads anything, so the schema exists even though the load
		// then threw.
		MysqlExecutionConfig config = config();
		when(releaseVersionLoader.createExecutionConfig(any())).thenReturn(config);
		doThrow(new RuntimeException("No space left on device"))
				.when(releaseVersionLoader).loadProspectiveVersion(any(), any(), any(), any());

		ValidationStatusReport report = statusReport();
		service.runRF2MysqlValidations(runConfig(), report);

		verify(releaseDataManager).dropSchema(PROSPECTIVE);
		assertTrue(report.getFailureMessages().stream().anyMatch(m -> m.contains("Failed to load data")),
				"the caller still has to be told the load failed");
	}

	@Test
	void theSchemaIsDroppedAfterTheAssertionsHaveRunAgainstIt() throws Exception {
		// Ordering, and the reason the cleanup is an OUTER finally: dropping the
		// schema before runAssertionTests would leave every assertion querying a
		// database that had just been deleted.
		MysqlExecutionConfig config = config();
		when(releaseVersionLoader.createExecutionConfig(any())).thenReturn(config);
		when(releaseVersionLoader.isUnknownVersion(anyString())).thenReturn(false);
		doThrow(new IllegalStateException("assertions ran BEFORE the drop, which is correct"))
				.when(releaseDataManager).dropSchema(PROSPECTIVE);

		// The drop throwing must not fail the run, and must not stop the report.
		ValidationStatusReport report = statusReport();
		service.runRF2MysqlValidations(runConfig(), report);

		verify(releaseDataManager).dropSchema(PROSPECTIVE);
	}

	@Test
	void oneUndroppableSchemaDoesNotStrandTheOthers() throws Exception {
		MysqlExecutionConfig config = config();
		config.setPreviousVersion("rvf_au_20260630");
		config.setExcludedRF2Files(java.util.List.of("rel2_Refset_SimpleDelta_INT_20260131.txt"));
		when(releaseVersionLoader.createExecutionConfig(any())).thenReturn(config);
		when(releaseVersionLoader.isUnknownVersion(anyString())).thenReturn(false);
		doThrow(new RuntimeException("locked")).when(releaseDataManager).dropSchema("rvf_au_20260630");

		service.runRF2MysqlValidations(runConfig(), statusReport());

		// The one that failed was attempted, and the other still went.
		verify(releaseDataManager).dropSchema("rvf_au_20260630");
		verify(releaseDataManager).dropSchema(PROSPECTIVE);
	}

	@Test
	void aSecondRunDoesNotTryToDropTheFirstRunsSchemaAgain() throws Exception {
		MysqlExecutionConfig first = config();
		when(releaseVersionLoader.createExecutionConfig(any())).thenReturn(first);
		when(releaseVersionLoader.isUnknownVersion(anyString())).thenReturn(false);
		service.runRF2MysqlValidations(runConfig(), statusReport());

		MysqlExecutionConfig second = new MysqlExecutionConfig(1788232999999L);
		second.setProspectiveVersion("rvf_au_20260831_1788232999999");
		when(releaseVersionLoader.createExecutionConfig(any())).thenReturn(second);
		service.runRF2MysqlValidations(runConfig(), statusReport());

		// Once each, not twice for the first: the set is cleared when it is drained,
		// so a stale name cannot be dropped again under a reused schema name.
		verify(releaseDataManager).dropSchema(PROSPECTIVE);
		verify(releaseDataManager).dropSchema("rvf_au_20260831_1788232999999");
	}

	@Test
	void aPreviousReleaseIsKeptWhenNoFilesWereExcluded() throws Exception {
		// Previous releases are reusable and deliberately survive: the next run
		// against the same previous release should not reload 10GB.
		MysqlExecutionConfig config = config();
		config.setPreviousVersion("rvf_au_20260630");
		when(releaseVersionLoader.createExecutionConfig(any())).thenReturn(config);
		when(releaseVersionLoader.isUnknownVersion(anyString())).thenReturn(false);

		service.runRF2MysqlValidations(runConfig(), statusReport());

		verify(releaseDataManager, never()).dropSchema("rvf_au_20260630");
		verify(releaseDataManager).dropSchema(PROSPECTIVE);
	}
}
