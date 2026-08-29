package org.ihtsdo.rvf.core.service.duck;

import org.ihtsdo.rvf.core.data.model.Assertion;
import org.ihtsdo.rvf.core.data.model.TestRunItem;
import org.ihtsdo.rvf.core.service.WhitelistService;
import org.ihtsdo.rvf.core.service.config.MysqlExecutionConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The three DuckDB classes composed: source -> execution -> extraction.
 *
 * <p>Each has its own unit tests, and each passed while the path as a whole was
 * broken. DuckDbAssertionExecutionService WROTE qa_result keyed on the numeric
 * assertionId while DuckFailuresExtractor READ it keyed on the uuid, so the
 * extractor found no rows and every assertion would have been reported as a
 * clean pass - a wrong answer, not an error. Nothing either class could assert
 * about itself would catch that; only running them against each other does.
 *
 * <p>DuckAssertionSource makes the disagreement unrecoverable rather than merely
 * wrong, which is why it belongs in the same test: it builds Assertions from the
 * store, which has no numeric id to set, so an execution service keyed on
 * getAssertionId() would bind the literal string "null" for every assertion and
 * collapse every finding into one bucket.
 */
class DuckEnginePipelineTest {

	private static final String UUID_INACTIVE = "11111111-1111-1111-1111-111111111111";
	private static final String UUID_CLEAN = "22222222-2222-2222-2222-222222222222";

	private static final String STORE = """
			{
			 "formatVersion": 1,
			 "sentinels": [
			  {"placeholder": "<RUNID>", "sentinel": "424242424242424242"},
			  {"placeholder": "<ASSERTIONUUID>", "sentinel": "rvfph_assertionuuid_"},
			  {"placeholder": "<PROSPECTIVE>", "sentinel": "rvfph_prospective_"}
			 ],
			 "tableColumns": {},
			 "ports": [],
			 "prerequisites": [],
			 "assertions": {
			  "11111111-1111-1111-1111-111111111111": {
			   "file": "inactive.sql", "text": "No inactive concepts", "keywords": "component-centric-validation",
			   "statements": [
				"insert into qa_result (run_id, assertion_id, concept_id, details, component_id, table_name) select 424242424242424242, 'rvfph_assertionuuid_', id, 'inactive concept', id, 'prospective.concept_s' from rvfph_prospective_.concept_s where active = '0'"]},
			  "22222222-2222-2222-2222-222222222222": {
			   "file": "clean.sql", "text": "Nothing wrong here", "keywords": "component-centric-validation",
			   "statements": [
				"insert into qa_result (run_id, assertion_id, concept_id, details) select 424242424242424242, 'rvfph_assertionuuid_', id, 'never' from rvfph_prospective_.concept_s where 1 = 0"]}
			 }
			}
			""";

	// Minimal but real: resolveGroups applies the shipped rule engine, so the
	// group name below is what selects the assertions, exactly as production
	// selects its nine groups.
	private static final String GROUPS_XML = """
			<assertionGroupingStrategy>
			 <group name="component-centric-validation" includeStandaloneCategories="component-centric-validation" />
			</assertionGroupingStrategy>
			""";

	private static final String POLICIES_XML = "<policyValues/>";

	private Connection con;
	private WhitelistService whitelistService;

	@BeforeEach
	void setUp() throws Exception {
		con = DriverManager.getConnection("jdbc:duckdb:");
		try (Statement st = con.createStatement()) {
			st.execute("CREATE SCHEMA prospective");
			st.execute("SET search_path='prospective'");
			st.execute("SET old_implicit_casting=true");
			st.execute("CREATE TABLE prospective.concept_s("
					+ "id BIGINT, effectivetime VARCHAR, active VARCHAR, moduleid VARCHAR)");
			st.execute("INSERT INTO prospective.concept_s VALUES "
					+ "(1,'20260831','1','32506021000036107'), "
					+ "(2,'20260831','0','32506021000036107'), "
					+ "(3,'20260831','0','32506021000036107')");
			st.execute("CREATE SCHEMA rvf_results");
			st.execute("CREATE TABLE rvf_results.qa_result(run_id BIGINT, assertion_id VARCHAR, "
					+ "concept_id BIGINT, details VARCHAR, component_id VARCHAR, "
					+ "table_name VARCHAR, skip_module_check VARCHAR)");
		}
		whitelistService = mock(WhitelistService.class);
		when(whitelistService.isWhitelistDisabled()).thenReturn(true);
	}

	@AfterEach
	void tearDown() throws Exception {
		con.close();
	}

	@Test
	void assertionsFlowFromStoreToReportWithoutADatabaseAnywhere() throws Exception {
		DuckStore store = DuckStore.parse(STORE);
		DuckAssertionSource source = DuckAssertionSource.from(store,
				new ByteArrayInputStream(GROUPS_XML.getBytes(StandardCharsets.UTF_8)),
				new ByteArrayInputStream(POLICIES_XML.getBytes(StandardCharsets.UTF_8)));

		List<Assertion> assertions = source.getAssertionsInGroups(List.of("component-centric-validation"));
		assertEquals(2, assertions.size(), "group resolution should select both assertions");
		// The store has no numeric id to give, which is the point: nothing
		// downstream may depend on one.
		assertions.forEach(a -> assertNull(a.getAssertionId()));

		DuckBinder binder = new DuckBinder(store.sentinels(), new DuckBinder.Config(
				7L, "prospective", null, null, "rvf_results.qa_result", null, List.of(), "20260831"));
		List<TestRunItem> items = new DuckDbAssertionExecutionService(store, binder, con)
				.execute(assertions);

		MysqlExecutionConfig config = new MysqlExecutionConfig(7L);
		config.setFailureExportMax(100);
		// DuckAssertionSource::findAll IS the supplier the extractor wants -
		// assertions carrying their resolved groups, from the store. No
		// AssertionService, so nothing here needs MySQL.
		new DuckFailuresExtractor(con, "rvf_results.qa_result", whitelistService, source::findAll)
				.extractTestResults(items, config);

		TestRunItem inactive = items.stream()
				.filter(i -> UUID_INACTIVE.equals(String.valueOf(i.getAssertionUuid()))).findFirst().orElseThrow();
		TestRunItem clean = items.stream()
				.filter(i -> UUID_CLEAN.equals(String.valueOf(i.getAssertionUuid()))).findFirst().orElseThrow();

		// Two inactive concepts, found and attributed to the right assertion.
		assertNull(inactive.getFailureMessage());
		assertEquals(2L, inactive.getFailureCount());
		assertEquals(2, inactive.getFirstNInstances().size());

		// A genuine pass: ran, found nothing. Distinguishable from the broken
		// state only because the other assertion found something - a keying
		// disagreement reports BOTH as zero.
		assertNull(clean.getFailureMessage());
		assertEquals(0L, clean.getFailureCount());
		// The extractor sets null; TestRunItem's getter substitutes an empty
		// list, so the report carries no instances either way.
		assertTrue(clean.getFirstNInstances().isEmpty());
	}

	@Test
	void failureDetailsAreEnrichedFromTheReleaseSchemaOnTheSameConnection() throws Exception {
		DuckStore store = DuckStore.parse(STORE);
		DuckAssertionSource source = DuckAssertionSource.from(store,
				new ByteArrayInputStream(GROUPS_XML.getBytes(StandardCharsets.UTF_8)),
				new ByteArrayInputStream(POLICIES_XML.getBytes(StandardCharsets.UTF_8)));
		DuckBinder binder = new DuckBinder(store.sentinels(), new DuckBinder.Config(
				7L, "prospective", null, null, "rvf_results.qa_result", null, List.of(), "20260831"));

		List<Assertion> assertions = source.getAssertionsInGroups(List.of("component-centric-validation"));
		List<TestRunItem> items = new DuckDbAssertionExecutionService(store, binder, con).execute(assertions);

		// Enrichment happens only on the whitelist-enabled path, exactly as in
		// MysqlFailuresExtractor - moduleId is read to decide whitelist
		// eligibility, so with whitelisting off there is nothing to read it for.
		when(whitelistService.isWhitelistDisabled()).thenReturn(false);
		when(whitelistService.checkComponentFailuresAgainstWhitelist(any())).thenReturn(List.of());

		MysqlExecutionConfig config = new MysqlExecutionConfig(7L);
		config.setFailureExportMax(100);
		// DuckAssertionSource::findAll IS the supplier the extractor wants -
		// assertions carrying their resolved groups, from the store. No
		// AssertionService, so nothing here needs MySQL.
		new DuckFailuresExtractor(con, "rvf_results.qa_result", whitelistService, source::findAll)
				.extractTestResults(items, config);

		TestRunItem inactive = items.stream()
				.filter(i -> UUID_INACTIVE.equals(String.valueOf(i.getAssertionUuid()))).findFirst().orElseThrow();
		// table_name written by the assertion resolves to a real table in another
		// schema, so moduleId comes back - the enrichment path end to end,
		// including the catalog check that guards the concatenated identifier.
		assertNotNull(inactive.getFirstNInstances().get(0).getModuleId());
		assertEquals("32506021000036107", inactive.getFirstNInstances().get(0).getModuleId());
	}
}
