package org.ihtsdo.rvf.core.service.duck;

import org.ihtsdo.rvf.core.data.model.Assertion;
import org.ihtsdo.rvf.core.data.model.TestRunItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end against a real in-process DuckDB, because the parts of this that
 * can go wrong are the parts a mock would paper over: whether the bound SQL
 * actually executes, whether qa_result ends up with the assertion id the report
 * joins on, and whether an assertion that could not run says so.
 */
class DuckDbAssertionExecutionServiceTest {

	private static final String STORE = """
			{
			 "formatVersion": 1,
			 "sentinels": [
			  {"placeholder": "<RUNID>", "sentinel": "424242424242424242"},
			  {"placeholder": "<ASSERTIONUUID>", "sentinel": "rvfph_assertionuuid_"},
			  {"placeholder": "<PROSPECTIVE>", "sentinel": "rvfph_prospective_"},
			  {"placeholder": "<PREVIOUS>", "sentinel": "rvfph_previous_"}
			 ],
			 "tableColumns": {},
			 "ports": ["CREATE OR REPLACE MACRO rvfph_prospective_.isOne(x) AS x = 1",
           "create table rvfph_prospective_.derived_from_prereq as select count(*) n from rvfph_prospective_.concept_active"],
			 "prerequisites": [
			  {"file": "pre-requisites.sql", "statements": [
			   "create table rvfph_prospective_.concept_active as select id from rvfph_prospective_.concept_s where active = '1'"]}
			 ],
			 "assertions": {
			  "11111111-1111-1111-1111-111111111111": {
			   "file": "finds-two.sql", "text": "Two inactive concepts", "keywords": "k",
			   "statements": [
				"insert into qa_result (run_id, assertion_id, concept_id, details) select 424242424242424242, 'rvfph_assertionuuid_', id, 'inactive' from rvfph_prospective_.concept_s where active = '0'"]},
			  "22222222-2222-2222-2222-222222222222": {
			   "file": "needs-previous.sql", "text": "Needs previous", "keywords": "k",
			   "statements": [
				"insert into qa_result (run_id, assertion_id, concept_id, details) select 424242424242424242, 'rvfph_assertionuuid_', id, 'x' from rvfph_previous_.concept_s"]},
			  "33333333-3333-3333-3333-333333333333": {
			   "file": "broken.sql", "text": "Broken SQL", "keywords": "k",
			   "statements": ["select * from rvfph_prospective_.no_such_table"]},
			  "44444444-4444-4444-4444-444444444444": {
			   "file": "uses-port.sql", "text": "Uses a ported macro", "keywords": "k",
			   "statements": [
				"insert into qa_result (run_id, assertion_id, concept_id, details) select 424242424242424242, 'rvfph_assertionuuid_', id, 'one' from rvfph_prospective_.concept_s where isOne(id)"]}
			 }
			}
			""";

	private Connection con;
	private DuckDbAssertionExecutionService service;

	@BeforeEach
	void setUp() throws Exception {
		con = DriverManager.getConnection("jdbc:duckdb:");
		try (Statement st = con.createStatement()) {
			st.execute("CREATE SCHEMA prospective");
			st.execute("SET search_path='prospective'");
			st.execute("SET old_implicit_casting=true");
			st.execute("CREATE TABLE prospective.concept_s(id BIGINT, active VARCHAR)");
			st.execute("INSERT INTO prospective.concept_s VALUES (1,'1'),(2,'0'),(3,'0')");
			st.execute("CREATE SCHEMA rvf_results");
			st.execute("CREATE TABLE rvf_results.qa_result(run_id BIGINT, assertion_id VARCHAR, "
					+ "concept_id BIGINT, details VARCHAR, component_id VARCHAR, "
					+ "table_name VARCHAR, skip_module_check VARCHAR)");
		}
		DuckStore store = DuckStore.parse(STORE);
		// No previous release, which is what makes the skip path testable.
		DuckBinder binder = new DuckBinder(store.sentinels(), new DuckBinder.Config(
				7L, "prospective", null, null, "rvf_results.qa_result", null, List.of(), "20260831"));
		service = new DuckDbAssertionExecutionService(store, binder, con);
	}

	@AfterEach
	void tearDown() throws Exception {
		con.close();
	}

	private static Assertion assertion(String uuid, long id, String text) {
		Assertion a = new Assertion();
		a.setUuid(UUID.fromString(uuid));
		a.setAssertionId(id);
		a.setAssertionText(text);
		a.setKeywords("k");
		a.setSeverity("ERROR");
		return a;
	}

	@Test
	void prepareSchemaAppliesEverySetupStatement() {
		// The publisher emits nothing here it expects to fail: MySQL routine
		// definitions are dropped at publish time, and so is the one CALL whose
		// DuckDB equivalent is a port. So the tolerated failure count is zero.
		DuckDbAssertionExecutionService.SetupResult r = service.prepareSchema();
		assertEquals(3, r.applied());
		assertTrue(r.failures().isEmpty());
	}

	@Test
	void aFailedSetupStatementAbortsTheRunRatherThanDegradingIt() throws Exception {
		// The regression this exists to prevent: ports-first ordering lost 12 of
		// 45 setup statements and the findings total did not move by one row,
		// because the machinery lost belonged to a corpus that run did not
		// exercise. Reporting results off a half-built schema is worse than
		// failing, so any setup failure is fatal.
		DuckStore broken = DuckStore.parse(STORE.replace(
				"\"ports\": [", "\"ports\": [\"create table x as select * from no_such_relation\", "));
		DuckDbAssertionExecutionService svc = new DuckDbAssertionExecutionService(broken,
				new DuckBinder(broken.sentinels(), new DuckBinder.Config(7L, "prospective",
						null, null, "rvf_results.qa_result", null, List.of(), "20260831")),
				con);

		DuckDbAssertionExecutionService.SetupFailedException e = assertThrows(
				DuckDbAssertionExecutionService.SetupFailedException.class, svc::prepareSchema);
		assertEquals(1, e.getFailures().size());
		assertTrue(e.getFailures().get(0).contains("no_such_relation"), e.getFailures().get(0));
		assertTrue(e.getMessage().contains("no assertion result from this run is trustworthy"), e.getMessage());
	}

	@Test
	void portsAreAppliedAfterPrerequisitesSoAPortCanUseAPrerequisiteTable() throws Exception {
		// Not a style preference. transitiveClosureTable is a PORT built from
		// relationship_active, a PRE-REQUISITE table, so ports-first breaks it -
		// and breaks it invisibly, because the macros then lost belong to a
		// corpus the failing run may not even exercise.
		service.prepareSchema();
		try (Statement st = con.createStatement();
				java.sql.ResultSet rs = st.executeQuery(
						"select n from prospective.derived_from_prereq")) {
			assertTrue(rs.next());
			assertEquals(1, rs.getInt(1));
		}
	}

	@Test
	void anAssertionWritesToQaResultUnderItsAssertionIdNotItsUuid() throws Exception {
		service.prepareSchema();
		List<TestRunItem> items = service.execute(
				List.of(assertion("11111111-1111-1111-1111-111111111111", 501L, "Two inactive concepts")));

		assertEquals(1, items.size());
		assertNull(items.get(0).getFailureMessage());
		assertNotNull(items.get(0).getRunTime());
		// The report joins qa_result on the numeric assertion id, which is also
		// what RVF binds <ASSERTIONUUID> to. Writing the uuid here would produce
		// findings that join to nothing.
		Map<String, Long> counts = service.failureCounts(7L, "rvf_results.qa_result");
		assertEquals(Map.of("501", 2L), counts);
	}

	@Test
	void anAssertionNeedingAnUnsuppliedReleaseSaysSoRatherThanPassing() {
		service.prepareSchema();
		TestRunItem item = service.execute(
				List.of(assertion("22222222-2222-2222-2222-222222222222", 502L, "Needs previous"))).get(0);
		// A zero failure count would report a PASS for a check nothing ran.
		assertNotNull(item.getFailureMessage());
		assertTrue(item.getFailureMessage().contains("<PREVIOUS>"), item.getFailureMessage());
		assertTrue(item.getFailureMessage().startsWith("Not run:"), item.getFailureMessage());
	}

	@Test
	void aFailingStatementIsReportedAgainstItsOwnAssertionAndDoesNotStopTheRest() throws Exception {
		service.prepareSchema();
		List<TestRunItem> items = service.execute(List.of(
				assertion("33333333-3333-3333-3333-333333333333", 503L, "Broken SQL"),
				assertion("11111111-1111-1111-1111-111111111111", 501L, "Two inactive concepts")));

		assertNotNull(items.get(0).getFailureMessage());
		assertNull(items.get(1).getFailureMessage());
		assertEquals(Map.of("501", 2L), service.failureCounts(7L, "rvf_results.qa_result"));
	}

	@Test
	void anAssertionCanUseAPortedMacro() throws Exception {
		service.prepareSchema();
		TestRunItem item = service.execute(
				List.of(assertion("44444444-4444-4444-4444-444444444444", 504L, "Uses a ported macro"))).get(0);
		assertNull(item.getFailureMessage());
		assertEquals(1L, service.failureCounts(7L, "rvf_results.qa_result").get("504"));
	}

	@Test
	void anAssertionMissingFromTheStoreIsReportedNotSilentlyPassed() {
		TestRunItem item = service.execute(
				List.of(assertion("99999999-9999-9999-9999-999999999999", 599L, "Not published"))).get(0);
		assertNotNull(item.getFailureMessage());
		assertTrue(item.getFailureMessage().contains("out of step"), item.getFailureMessage());
	}
}
