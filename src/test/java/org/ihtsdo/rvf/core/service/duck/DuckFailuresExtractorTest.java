package org.ihtsdo.rvf.core.service.duck;

import org.ihtsdo.rvf.core.data.model.Assertion;
import org.ihtsdo.rvf.core.data.model.AssertionGroup;
import org.ihtsdo.rvf.core.data.model.FailureDetail;
import org.ihtsdo.rvf.core.data.model.TestRunItem;
import org.ihtsdo.rvf.core.service.AssertionService;
import org.ihtsdo.rvf.core.service.WhitelistService;
import org.ihtsdo.rvf.core.service.config.MysqlExecutionConfig;
import org.ihtsdo.rvf.core.service.whitelist.WhitelistItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Against a real in-process DuckDB, for the same reason
 * {@link DuckDbAssertionExecutionServiceTest} is: the failure mode that matters
 * here is a wrong query, not a wrong mock - a reversed limit/offset bind, or a
 * positional column lookup landing on the wrong value, both execute without
 * error and just answer a different question.
 */
class DuckFailuresExtractorTest {

	private static final String QA_RESULT = "rvf_results.qa_result";

	private Connection con;
	private AssertionService assertionService;
	private WhitelistService whitelistService;

	@BeforeEach
	void setUp() throws Exception {
		con = DriverManager.getConnection("jdbc:duckdb:");
		try (Statement st = con.createStatement()) {
			st.execute("CREATE SCHEMA prospective");
			// Column order matters: setModuleAndFullFields reads moduleId at
			// position 4 by POSITION, mirroring an RF2 concept file's own column
			// order (id, effectiveTime, active, moduleId, definitionStatusId).
			st.execute("CREATE TABLE prospective.concept_s(id BIGINT, effectivetime VARCHAR, "
					+ "active VARCHAR, moduleid VARCHAR, definitionstatusid BIGINT)");
			st.execute("INSERT INTO prospective.concept_s VALUES "
					+ "(100,'20260101','1','mod-included',900000000000074008),"
					+ "(200,'20260101','1','mod-excluded',900000000000074008),"
					+ "(300,'20260101','1','mod-excluded',900000000000074008)");

			st.execute("CREATE SCHEMA rvf_results");
			st.execute("CREATE TABLE rvf_results.qa_result(run_id BIGINT, assertion_id VARCHAR, "
					+ "concept_id BIGINT, details VARCHAR, component_id VARCHAR, "
					+ "table_name VARCHAR, skip_module_check BOOLEAN)");
		}
		assertionService = mock(AssertionService.class);
		whitelistService = mock(WhitelistService.class);
	}

	@AfterEach
	void tearDown() throws Exception {
		con.close();
	}

	private void insertQaResult(long runId, String assertionUuid, long conceptId, String componentId,
			String tableName, boolean skipModuleCheck) throws Exception {
		try (Statement st = con.createStatement()) {
			st.execute("INSERT INTO rvf_results.qa_result VALUES (" + runId + ",'" + assertionUuid + "',"
					+ conceptId + ",'detail',"
					+ (componentId == null ? "NULL" : "'" + componentId + "'") + ","
					+ (tableName == null ? "NULL" : "'" + tableName + "'") + "," + skipModuleCheck + ")");
		}
	}

	private static TestRunItem item(String uuid) {
		TestRunItem item = new TestRunItem();
		item.setAssertionUuid(UUID.fromString(uuid));
		return item;
	}

	private static Assertion assertion(String uuid) {
		Assertion a = new Assertion();
		a.setUuid(UUID.fromString(uuid));
		a.setAssertionId(1L);
		return a;
	}

	@Test
	void failureCountsAreGroupedByAssertionUuidNotByANumericId() throws Exception {
		// The uuid IS the key: no uuidToAssertionIdMap indirection to get wrong.
		insertQaResult(7L, "11111111-1111-1111-1111-111111111111", 1L, null, null, false);
		insertQaResult(7L, "11111111-1111-1111-1111-111111111111", 2L, null, null, false);

		DuckFailuresExtractor extractor = new DuckFailuresExtractor(con, QA_RESULT, whitelistService, assertionService);
		when(whitelistService.isWhitelistDisabled()).thenReturn(true);

		List<TestRunItem> items = List.of(
				item("11111111-1111-1111-1111-111111111111"),
				item("22222222-2222-2222-2222-222222222222"));
		MysqlExecutionConfig config = new MysqlExecutionConfig(7L);
		config.setFailureExportMax(10);

		extractor.extractTestResults(items, config);

		assertEquals(2L, items.get(0).getFailureCount());
		// Ran, found nothing in qa_result, no failure message: a clean pass, not
		// testsIncomplete.
		assertEquals(0L, items.get(1).getFailureCount());
		assertTrue(items.get(1).getFirstNInstances().isEmpty());
	}

	@Test
	void aFailureMessageWithNoQaResultRowsIsTestsIncompleteNotAPass() throws Exception {
		// No qa_result rows for this assertion (it never got that far) and a
		// failure message set: -1L says testsIncomplete rather than reading as a
		// clean pass, which a bare 0L would.
		DuckFailuresExtractor extractor = new DuckFailuresExtractor(con, QA_RESULT, whitelistService, assertionService);
		when(whitelistService.isWhitelistDisabled()).thenReturn(true);

		TestRunItem failed = item("33333333-3333-3333-3333-333333333333");
		failed.setFailureMessage("statement timeout");

		extractor.extractTestResults(List.of(failed), new MysqlExecutionConfig(7L));

		assertEquals(-1L, failed.getFailureCount());
		assertTrue(failed.getFirstNInstances().isEmpty());
	}

	@Test
	void failureExportMaxCapsFirstNInstancesButNotTheReportedTotal() throws Exception {
		String uuid = "44444444-4444-4444-4444-444444444444";
		for (long i = 1; i <= 5; i++) {
			insertQaResult(7L, uuid, i, null, null, false);
		}

		DuckFailuresExtractor extractor = new DuckFailuresExtractor(con, QA_RESULT, whitelistService, assertionService);
		when(whitelistService.isWhitelistDisabled()).thenReturn(true);

		TestRunItem testItem = item(uuid);
		MysqlExecutionConfig config = new MysqlExecutionConfig(7L);
		config.setFailureExportMax(3);

		extractor.extractTestResults(List.of(testItem), config);

		assertEquals(5L, testItem.getFailureCount());
		assertEquals(3, testItem.getFirstNInstances().size());
	}

	@Test
	void pagingFetchesEveryBatchWithoutLossOrDuplication() throws Exception {
		// Exercises the whitelist-batching loop with a batch size smaller than the
		// total, which is what makes a reversed limit/offset bind observable: get
		// it backwards and the first "limit 0 offset <n>" batch silently returns
		// zero rows forever, so the run under-reports instead of failing.
		String uuid = "55555555-5555-5555-5555-555555555555";
		Set<Long> expected = new HashSet<>();
		for (long i = 1; i <= 5; i++) {
			insertQaResult(7L, uuid, i, null, null, false);
			expected.add(i);
		}

		DuckFailuresExtractor extractor = new DuckFailuresExtractor(con, QA_RESULT, whitelistService, assertionService, 2);
		when(whitelistService.isWhitelistDisabled()).thenReturn(false);
		when(assertionService.findAll()).thenReturn(List.of(assertion(uuid)));
		when(assertionService.getAllAssertionGroups()).thenReturn(Collections.emptyList());
		when(whitelistService.checkComponentFailuresAgainstWhitelist(any())).thenReturn(Collections.emptyList());

		TestRunItem testItem = item(uuid);
		MysqlExecutionConfig config = new MysqlExecutionConfig(7L);
		config.setFailureExportMax(100);

		extractor.extractTestResults(List.of(testItem), config);

		assertEquals(5L, testItem.getFailureCount());
		Set<Long> actual = new HashSet<>();
		for (FailureDetail detail : testItem.getFirstNInstances()) {
			actual.add(Long.valueOf(detail.getConceptId()));
		}
		assertEquals(expected, actual);
	}

	@Test
	void moduleFilteringKeepsIncludedModulesAndBypassesForSkipModuleCheck() throws Exception {
		String uuid = "66666666-6666-6666-6666-666666666666";
		// concept 100 -> mod-included: kept because it is in includedModules.
		insertQaResult(7L, uuid, 100L, "100", "prospective.concept_s", false);
		// concept 200 -> mod-excluded, no bypass: filtered out.
		insertQaResult(7L, uuid, 200L, "200", "prospective.concept_s", false);
		// concept 300 -> mod-excluded, but skipModuleCheck=true bypasses the filter.
		insertQaResult(7L, uuid, 300L, "300", "prospective.concept_s", true);

		Assertion assertionInGroup = assertion(uuid);
		AssertionGroup group = new AssertionGroup();
		group.setName("common-authoring");
		group.getAssertions().add(assertionInGroup);

		DuckFailuresExtractor extractor = new DuckFailuresExtractor(con, QA_RESULT, whitelistService, assertionService);
		when(whitelistService.isWhitelistDisabled()).thenReturn(false);
		when(assertionService.findAll()).thenReturn(List.of(assertionInGroup));
		when(assertionService.getAllAssertionGroups()).thenReturn(List.of(group));
		when(whitelistService.checkComponentFailuresAgainstWhitelist(any())).thenReturn(Collections.emptyList());

		TestRunItem testItem = item(uuid);
		MysqlExecutionConfig config = new MysqlExecutionConfig(7L);
		config.setFailureExportMax(100);
		config.setExtensionValidation(true);
		config.setIncludedModules(List.of("mod-included"));

		extractor.extractTestResults(List.of(testItem), config);

		// 3 total, 1 filtered out (concept 200), none whitelisted -> 2 remain.
		assertEquals(2L, testItem.getFailureCount());
		Set<String> keptComponentIds = new HashSet<>();
		for (FailureDetail detail : testItem.getFirstNInstances()) {
			keptComponentIds.add(detail.getComponentId());
		}
		assertEquals(Set.of("100", "300"), keptComponentIds);
	}

	@Test
	void setModuleAndFullFieldsReadsModuleIdByPositionFromTheReleaseSchema() throws Exception {
		String uuid = "77777777-7777-7777-7777-777777777777";
		insertQaResult(7L, uuid, 100L, "100", "prospective.concept_s", false);

		DuckFailuresExtractor extractor = new DuckFailuresExtractor(con, QA_RESULT, whitelistService, assertionService);
		when(whitelistService.isWhitelistDisabled()).thenReturn(false);
		when(assertionService.findAll()).thenReturn(List.of(assertion(uuid)));
		when(assertionService.getAllAssertionGroups()).thenReturn(Collections.emptyList());
		when(whitelistService.checkComponentFailuresAgainstWhitelist(any())).thenReturn(Collections.emptyList());

		TestRunItem testItem = item(uuid);
		MysqlExecutionConfig config = new MysqlExecutionConfig(7L);
		config.setFailureExportMax(100);

		extractor.extractTestResults(List.of(testItem), config);

		assertEquals(1, testItem.getFirstNInstances().size());
		FailureDetail detail = testItem.getFirstNInstances().get(0);
		// Position 4 in concept_s (id, effectivetime, active, moduleid, ...).
		assertEquals("mod-included", detail.getModuleId());
		// Every column bar id/effectiveTime, comma-joined: active, moduleid,
		// definitionstatusid.
		assertEquals("1,mod-included,900000000000074008", detail.getFullComponent());
	}

	@Test
	void aTableNameThatIsNotAKnownTableIsNeverConcatenatedIntoAQuery() throws Exception {
		// The table name cannot be a bind parameter, so it is concatenated - and
		// therefore validated against the catalog first. Every table_name in the
		// corpus is a quoted literal today, so this is not reachable from a
		// submitted release package; it is guarded because that is a property of
		// the assertion corpus rather than of this code.
		String uuid = "88888888-8888-8888-8888-888888888888";
		insertQaResult(7L, uuid, 100L, "100",
				"prospective.concept_s where 1=0 union all select * from prospective.concept_s --",
				false);

		DuckFailuresExtractor extractor = new DuckFailuresExtractor(con, QA_RESULT, whitelistService, assertionService);
		when(whitelistService.isWhitelistDisabled()).thenReturn(false);
		when(assertionService.findAll()).thenReturn(List.of(assertion(uuid)));
		when(assertionService.getAllAssertionGroups()).thenReturn(Collections.emptyList());
		when(whitelistService.checkComponentFailuresAgainstWhitelist(any())).thenReturn(Collections.emptyList());

		TestRunItem testItem = item(uuid);
		MysqlExecutionConfig config = new MysqlExecutionConfig(7L);
		config.setFailureExportMax(100);

		extractor.extractTestResults(List.of(testItem), config);

		// The finding still surfaces - enrichment is additive, so refusing to
		// enrich must never lose a failure - but nothing was executed for it.
		assertEquals(1L, testItem.getFailureCount());
		assertEquals(1, testItem.getFirstNInstances().size());
		assertNull(testItem.getFirstNInstances().get(0).getModuleId());
	}

	@Test
	void aQaResultTableNameThatIsNotAnIdentifierIsRejectedAtConstruction() {
		assertThrows(IllegalArgumentException.class, () -> new DuckFailuresExtractor(
				con, "qa_result; drop table concept_s", whitelistService, assertionService));
	}
}
