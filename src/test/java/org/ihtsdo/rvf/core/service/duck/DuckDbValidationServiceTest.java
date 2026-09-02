package org.ihtsdo.rvf.core.service.duck;

import org.ihtsdo.rvf.core.data.model.TestRunItem;
import org.ihtsdo.rvf.core.data.model.TestType;
import org.ihtsdo.rvf.core.data.model.ValidationReport;
import org.ihtsdo.rvf.core.service.ReleaseAcquisitionService;
import org.ihtsdo.rvf.core.service.ValidationReportService;
import org.ihtsdo.rvf.core.service.WhitelistService;
import org.ihtsdo.rvf.core.service.config.MysqlExecutionConfig;
import org.ihtsdo.rvf.core.service.config.ValidationRunConfig;
import org.ihtsdo.rvf.core.service.pojo.ValidationStatusReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A whole validation, against a real DuckDB and a real RF2 release on disk.
 *
 * <p>Nothing here is stubbed except the two collaborators that talk to the
 * outside world (progress reporting to S3, the whitelist gateway). In
 * particular there is no MySQL, no Spring context and no mocked store: the
 * release is materialised, the pre-requisites and ports are applied, the
 * assertions execute and qa_result is read back, because the failures this
 * class can have are all failures of ORDER between those steps and no test that
 * stubs one of them can see them.
 *
 * <p>The fixture is built so that each expected count can only come out right
 * for one reason:
 * <ul>
 * <li>{@code inactive} calls a MACRO defined by the store's ports, so a count of
 *     2 means the ports were applied.
 * <li>{@code versionBound} selects from a table the RESOURCE assertion creates,
 *     which is itself built from a view the PRE-REQUISITES create - so a count
 *     of 1 means pre-requisites ran before ports-dependent work, resource
 *     assertions ran before group assertions, and {@code <VERSION>} bound to the
 *     release's own effectiveTime rather than to NOT_SUPPLIED.
 * <li>{@code needsPrevious} names {@code <PREVIOUS>}, so it is the one assertion
 *     whose outcome must CHANGE when a previous release is supplied.
 * </ul>
 */
class DuckDbValidationServiceTest {

	private static final String UUID_RESOURCE = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
	private static final String UUID_INACTIVE = "11111111-1111-1111-1111-111111111111";
	private static final String UUID_CLEAN = "22222222-2222-2222-2222-222222222222";
	private static final String UUID_NEEDS_PREVIOUS = "33333333-3333-3333-3333-333333333333";
	private static final String UUID_VERSION_BOUND = "44444444-4444-4444-4444-444444444444";

	private static final String GOOD_PREREQUISITE =
			"CREATE OR REPLACE VIEW concept_active AS SELECT * FROM concept_s WHERE active = '1'";

	/**
	 * Written unqualified on purpose: the real pre-requisites.sql is, and it
	 * only resolves because the service sets search_path to the prospective
	 * schema first.
	 */
	private static final String STORE = """
			{
			 "formatVersion": 1,
			 "generator": {"tool": "DuckDbValidationServiceTest"},
			 "sentinels": [
			  {"placeholder": "<RUNID>", "sentinel": "424242424242424242"},
			  {"placeholder": "<ASSERTIONUUID>", "sentinel": "rvfph_assertionuuid_"},
			  {"placeholder": "<PROSPECTIVE>", "sentinel": "rvfph_prospective_"},
			  {"placeholder": "<PREVIOUS>", "sentinel": "rvfph_previous_"},
			  {"placeholder": "<VERSION>", "sentinel": "rvfph_version_"}
			 ],
			 "tableColumns": {
			  "concept_s": "id BIGINT, effectivetime VARCHAR, active VARCHAR, moduleid BIGINT, definitionstatusid BIGINT",
			  "description_s": "id BIGINT, effectivetime VARCHAR, active VARCHAR, moduleid BIGINT, conceptid BIGINT, languagecode VARCHAR, typeid BIGINT, term VARCHAR, casesignificanceid BIGINT"
			 },
			 "prerequisites": [
			  {"file": "pre-requisites.sql", "statements": [
			   "CREATE OR REPLACE VIEW concept_active AS SELECT * FROM concept_s WHERE active = '1'"]}
			 ],
			 "ports": [
			  "CREATE OR REPLACE MACRO isInactive(a) AS (a = '0')"
			 ],
			 "assertions": {
			  "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa": {
			   "file": "resource.sql", "text": "Build the shared active-concept table",
			   "keywords": "resource", "severity": "",
			   "statements": [
			    "CREATE OR REPLACE TABLE rvfph_prospective_.res_edited_concepts AS SELECT id, effectivetime FROM rvfph_prospective_.concept_active"]},
			  "11111111-1111-1111-1111-111111111111": {
			   "file": "inactive.sql", "text": "No inactive concepts",
			   "keywords": "component-centric-validation", "severity": "",
			   "statements": [
			    "insert into qa_result (run_id, assertion_id, concept_id, details, component_id, table_name) select 424242424242424242, 'rvfph_assertionuuid_', id, 'inactive concept', id, 'prospective.concept_s' from rvfph_prospective_.concept_s where isInactive(active)"]},
			  "22222222-2222-2222-2222-222222222222": {
			   "file": "clean.sql", "text": "Nothing wrong here",
			   "keywords": "component-centric-validation", "severity": "",
			   "statements": [
			    "insert into qa_result (run_id, assertion_id, concept_id, details) select 424242424242424242, 'rvfph_assertionuuid_', id, 'never' from rvfph_prospective_.concept_s where 1 = 0"]},
			  "33333333-3333-3333-3333-333333333333": {
			   "file": "needs-previous.sql", "text": "Every concept existed in the previous release",
			   "keywords": "component-centric-validation", "severity": "",
			   "statements": [
			    "insert into qa_result (run_id, assertion_id, concept_id, details, component_id, table_name) select 424242424242424242, 'rvfph_assertionuuid_', p.id, 'new concept', p.id, 'prospective.concept_s' from rvfph_prospective_.concept_s p where not exists (select 1 from rvfph_previous_.concept_s v where v.id = p.id)"]},
			  "44444444-4444-4444-4444-444444444444": {
			   "file": "version-bound.sql", "text": "Active concepts at this release version",
			   "keywords": "component-centric-validation", "severity": "",
			   "statements": [
			    "insert into qa_result (run_id, assertion_id, concept_id, details, component_id, table_name) select 424242424242424242, 'rvfph_assertionuuid_', id, 'active at version', id, 'prospective.concept_s' from rvfph_prospective_.res_edited_concepts where effectivetime = 'rvfph_version_'"]}
			 }
			}
			""";

	private static final String GROUPS_XML = """
			<assertionGroupingStrategy>
			 <group name="component-centric-validation" includeStandaloneCategories="component-centric-validation" />
			</assertionGroupingStrategy>
			""";

	private static final String POLICIES_XML = "<policyValues/>";

	@TempDir
	private Path root;

	private Path storeFile;
	private Path corpus;
	private Path work;
	private Path prospective;
	private ValidationReportService reportService;
	private WhitelistService whitelistService;

	@BeforeEach
	void setUp() throws Exception {
		storeFile = root.resolve("store.json");
		Files.writeString(storeFile, STORE);

		corpus = root.resolve("corpus");
		Files.createDirectories(corpus);
		Files.writeString(corpus.resolve("groups.xml"), GROUPS_XML);
		Files.writeString(corpus.resolve("policies.xml"), POLICIES_XML);

		work = root.resolve("work");
		Files.createDirectories(work);

		// Two active concepts, one of them at an older effectiveTime, and two
		// inactive. Every expected count below is a different subset of these.
		prospective = root.resolve("prospective");
		writeRf2(prospective, "Snapshot/Terminology/sct2_Concept_Snapshot_AU1000036_20260831.txt",
				"""
				id	effectiveTime	active	moduleId	definitionStatusId
				1	20260831	1	32506021000036107	900000000000074008
				2	20260831	0	32506021000036107	900000000000074008
				3	20260831	0	32506021000036107	900000000000074008
				4	20250131	1	32506021000036107	900000000000074008
				""");

		reportService = mock(ValidationReportService.class);
		whitelistService = mock(WhitelistService.class);
		when(whitelistService.isWhitelistDisabled()).thenReturn(true);
	}

	@Test
	void aFullRunReportsEveryAssertionWithItsOwnFailureCount() throws Exception {
		ValidationRunConfig runConfig = new ValidationRunConfig();
		runConfig.setRunId(7L);
		runConfig.setGroupsList(List.of("component-centric-validation"));
		runConfig.setStorageLocation("s3-not-reached/");
		ValidationStatusReport statusReport = statusReport(runConfig);

		// The config-driven entry point, so createExecutionConfig is exercised
		// with no MySQL bean in existence - the claim that its half of
		// ValidationVersionLoader is engine-agnostic is only worth anything if
		// something runs it that way.
		service().runRF2DuckDbValidations(runConfig, statusReport,
				DuckDbValidationService.ReleaseDirectories.of(prospective));

		assertEquals(List.of(), statusReport.getFailureMessages(),
				"a run that completes has nothing to report as a system failure");
		ValidationReport report = statusReport.getResultReport();

		// The ported MACRO resolved, so the two inactive concepts were found.
		assertEquals(2L, item(report, UUID_INACTIVE).getFailureCount());
		assertEquals(2, item(report, UUID_INACTIVE).getFirstNInstances().size());
		// Ran, found nothing - not the same as did not run.
		assertEquals(0L, item(report, UUID_CLEAN).getFailureCount());
		assertNull(item(report, UUID_CLEAN).getFailureMessage());
		// Reads a table the resource assertion built from a pre-requisite view,
		// and filters on <VERSION>: 1 of the 2 active concepts is at 20260831.
		assertEquals(1L, item(report, UUID_VERSION_BOUND).getFailureCount());
		// The resource assertion itself produces no findings and is reported.
		assertEquals(0L, item(report, UUID_RESOURCE).getFailureCount());

		assertEquals(5, report.getTotalTestsRun(), "every selected assertion is reported");
		assertTrue(report.getAssertionsPassed().stream().allMatch(i -> i.getTestType() == TestType.SQL),
				"the DuckDB engine still reports SQL assertions as SQL");
		assertEquals(1, statusReport.getTotalRF2FilesLoaded(),
				"the report names the RF2 files the release actually contributed");
	}

	@Test
	void anAssertionNeedingAPreviousReleaseIsNotRunRatherThanPassed() throws Exception {
		ValidationStatusReport statusReport = statusReport(new ValidationRunConfig());

		service().runValidations(executionConfig(11L),
				DuckDbValidationService.ReleaseDirectories.of(prospective), null, statusReport);

		ValidationReport report = statusReport.getResultReport();
		TestRunItem notRun = item(report, UUID_NEEDS_PREVIOUS);
		// -1, never 0. A zero here would be indistinguishable in the report from
		// "compared against the previous release and found no differences",
		// which is the wrong answer rather than a missing one.
		assertEquals(-1L, notRun.getFailureCount());
		assertNotNull(notRun.getFailureMessage());
		assertTrue(notRun.getFailureMessage().contains("<PREVIOUS>"), notRun.getFailureMessage());
		assertEquals(1, report.getTotalTestsIncomplete());
		assertFalse(report.getAssertionsPassed().stream()
						.anyMatch(i -> UUID_NEEDS_PREVIOUS.equals(String.valueOf(i.getAssertionUuid()))),
				"an assertion nothing executed must never appear in the passed list");
	}

	@Test
	void supplyingAPreviousReleaseRunsTheAssertionThatNeedsIt() throws Exception {
		// The previous release holds concepts 1 and 2, so 3 and 4 are new.
		Path previous = root.resolve("previous");
		writeRf2(previous, "Snapshot/Terminology/sct2_Concept_Snapshot_AU1000036_20250131.txt",
				"""
				id	effectiveTime	active	moduleId	definitionStatusId
				1	20250131	1	32506021000036107	900000000000074008
				2	20250131	1	32506021000036107	900000000000074008
				""");
		ValidationStatusReport statusReport = statusReport(new ValidationRunConfig());

		service().runValidations(executionConfig(12L),
				new DuckDbValidationService.ReleaseDirectories(prospective, previous, null),
				null, statusReport);

		TestRunItem ran = item(statusReport.getResultReport(), UUID_NEEDS_PREVIOUS);
		assertNull(ran.getFailureMessage(), "the release it needed was supplied");
		assertEquals(2L, ran.getFailureCount(), "concepts 3 and 4 are not in the previous release");
		assertEquals(0, statusReport.getResultReport().getTotalTestsIncomplete());
	}

	@Test
	void aFailedSetupStatementAbandonsTheRunInsteadOfReportingResults() throws Exception {
		// A pre-requisite that cannot execute. Everything downstream of it would
		// still "work" - the assertions run, qa_result fills, the counts look
		// plausible - which is exactly why this must not be allowed to proceed.
		Files.writeString(storeFile, STORE.replace(GOOD_PREREQUISITE,
				"CREATE OR REPLACE VIEW concept_active AS SELECT * FROM no_such_table"));
		ValidationStatusReport statusReport = statusReport(new ValidationRunConfig());

		service().runValidations(executionConfig(13L),
				DuckDbValidationService.ReleaseDirectories.of(prospective), null, statusReport);

		assertEquals(1, statusReport.getFailureMessages().size());
		assertTrue(statusReport.getFailureMessages().get(0).contains("setup failed"),
				statusReport.getFailureMessages().get(0));
		assertTrue(statusReport.getReportSummary().containsKey(TestType.SQL.name()));
		assertEquals(0, statusReport.getResultReport().getTotalTestsRun(),
				"no assertion result may be reported from a half-built schema");
	}

	@Test
	void theRunsDatabaseFileIsDeletedWhenItFinishes() throws Exception {
		service().runValidations(executionConfig(14L),
				DuckDbValidationService.ReleaseDirectories.of(prospective), null,
				statusReport(new ValidationRunConfig()));

		try (var files = Files.list(work)) {
			assertEquals(List.of(), files.map(p -> p.getFileName().toString()).sorted().toList(),
					"the per-run database, its write-ahead log and its spill directory all go");
		}
	}

	@Test
	void aMissingReleaseDirectoryIsReportedAsAFailedRunNotAnException() throws Exception {
		ValidationStatusReport statusReport = statusReport(new ValidationRunConfig());

		service().runValidations(executionConfig(15L),
				DuckDbValidationService.ReleaseDirectories.of(root.resolve("no-such-release")),
				null, statusReport);

		assertEquals(1, statusReport.getFailureMessages().size());
		assertTrue(statusReport.getFailureMessages().get(0).contains("Prospective Release"),
				statusReport.getFailureMessages().get(0));
		assertEquals(0, statusReport.getResultReport().getTotalTestsRun());
	}

	private DuckDbValidationService service() {
		return new DuckDbValidationService(reportService, whitelistService,
				new ReleaseAcquisitionService(),
				new DuckStoreLocator(storeFile.toString(), corpus.toString()),
				corpus.toString(), work.toString(), "qa_result", 0, "", false, "", 0);
	}

	private DuckDbValidationService service(String memoryLimit) {
		return new DuckDbValidationService(reportService, whitelistService,
				new ReleaseAcquisitionService(),
				new DuckStoreLocator(storeFile.toString(), corpus.toString()),
				corpus.toString(), work.toString(), "qa_result", 0, memoryLimit, false, "", 0);
	}

	private static MysqlExecutionConfig executionConfig(long runId) {
		MysqlExecutionConfig config = new MysqlExecutionConfig(runId);
		config.setGroupNames(List.of("component-centric-validation"));
		config.setIncludedModules(List.of());
		config.setFailureExportMax(10);
		return config;
	}

	private static ValidationStatusReport statusReport(ValidationRunConfig runConfig) {
		ValidationStatusReport statusReport = new ValidationStatusReport(runConfig);
		statusReport.setResultReport(new ValidationReport());
		return statusReport;
	}

	/** The item for this assertion, wherever in the report it ended up. */
	private static TestRunItem item(ValidationReport report, String uuid) {
		UUID wanted = UUID.fromString(uuid);
		return java.util.stream.Stream.of(report.getAssertionsPassed(), report.getAssertionsFailed(),
						report.getAssertionsWarning(), report.getAssertionsSkipped())
				.flatMap(List::stream)
				.filter(i -> wanted.equals(i.getAssertionUuid()))
				.findFirst()
				.orElseThrow(() -> new AssertionError("no report item for assertion " + uuid));
	}

	private static void writeRf2(Path releaseDir, String relative, String content) throws Exception {
		Path file = releaseDir.resolve(relative);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}

	/**
	 * A bare number is accepted by Spring and then rejected by DuckDB when the
	 * connection opens - after acquisition and materialisation, so on a real
	 * edition the operator waits twenty-five minutes to learn the flag was wrong,
	 * and the SQL phase is lost while the other phases carry on and report
	 * partial results.
	 */
	@Test
	void aMemoryLimitWithoutAUnitIsRejectedAtStartup() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> service("4"));
		assertTrue(e.getMessage().contains("must carry a unit"), e.getMessage());
		assertTrue(e.getMessage().contains("4GB"), "the message has to show the fix");
	}

	@Test
	void unitsDuckDbAcceptsArePassedThrough() {
		for (String ok : new String[] {"4GB", "4GiB", "512MB", "1TB", "2.5GB", " 8GB "}) {
			assertNotNull(service(ok), "should accept " + ok);
		}
	}

	@Test
	void anAbsentMemoryLimitKeepsDuckDbsOwnHeuristic() {
		assertNotNull(service(""));
		assertNotNull(service(null));
	}
}
