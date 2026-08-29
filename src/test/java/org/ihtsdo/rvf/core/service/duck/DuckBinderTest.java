package org.ihtsdo.rvf.core.service.duck;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The binder has to agree with two other implementations - MySqlQueryTransformer
 * and the publisher's own bind() - and disagreements are silent: a wrong value
 * still produces SQL that runs. So each case here pins one agreement.
 */
class DuckBinderTest {

	/** The sentinel table the publisher emits, in its order. */
	private static final Map<String, String> SENTINELS = Map.of(
			"<RUNID>", "424242424242424242",
			"<ASSERTIONUUID>", "rvfph_assertionuuid_",
			"<MODULEID>", "rvfph_moduleid_",
			"<MODULEIDS>", "rvfph_moduleids_",
			"<INCLUDED_MODULES>", "rvfph_included_modules_",
			"<VERSION>", "rvfph_version_",
			"<PROSPECTIVE>", "rvfph_prospective_",
			"<TEMP>", "rvfph_temp_",
			"<PREVIOUS>", "rvfph_previous_",
			"<DEPENDENCY>", "rvfph_dependency_");

	private static DuckBinder binder(String previous, String dependency) {
		return new DuckBinder(SENTINELS, new DuckBinder.Config(7L, "prospective",
				previous, dependency, "rvf_results.qa_result", null, List.of(), null));
	}

	@Test
	void bindsTheRunsValuesIntoAStatement() {
		DuckBinder.Bound b = binder("previous", null).bind(
				"insert into qa_result select 424242424242424242, "
						+ "'rvfph_assertionuuid_' from rvfph_prospective_.concept_s", "31");
		assertEquals("insert into rvf_results.qa_result select 7, '31' "
				+ "from prospective.concept_s", b.sql());
		assertFalse(b.isSkipped());
	}

	@Test
	void tempResolvesToTheProspectiveSchemaJustAsMySqlQueryTransformerDoes() {
		assertEquals("create table prospective.v_x as select 1",
				binder(null, null).bind("create table rvfph_temp_.v_x as select 1", "1").sql());
	}

	@Test
	void aStatementNeedingAnUnsuppliedReleaseIsSkippedNotFailed() {
		DuckBinder.Bound b = binder(null, null)
				.bind("select 1 from rvfph_previous_.concept_s", "1");
		assertTrue(b.isSkipped());
		assertEquals("<PREVIOUS>", b.skippedFor());
		assertNull(b.sql());
	}

	@Test
	void dependencyIsReportedSeparatelyFromPrevious() {
		// The distinction matters: production supplies a previous release but
		// never a dependency, because it posts releaseAsAnEdition=true.
		DuckBinder.Bound b = binder("previous", null)
				.bind("select 1 from rvfph_previous_.concept_s "
						+ "join rvfph_dependency_.concept_s using (id)", "1");
		assertTrue(b.isSkipped());
		assertEquals("<DEPENDENCY>", b.skippedFor());
	}

	@Test
	void aSuppliedReleaseBinds() {
		assertEquals("select 1 from previous.concept_s",
				binder("previous", null).bind("select 1 from rvfph_previous_.concept_s", "1").sql());
	}

	@Test
	void moduleIdDefaultsToTheCoreModuleNotABlank() {
		// A blank here put '' into a BIGINT concept_id and killed the assertion
		// with "Could not convert string '' to INT64".
		assertEquals("select '900000000000207008'",
				binder(null, null).bind("select 'rvfph_moduleid_'", "1").sql());
	}

	@Test
	void versionDefaultsToNotSuppliedNotABlank() {
		assertEquals("select 'NOT_SUPPLIED'",
				binder(null, null).bind("select 'rvfph_version_'", "1").sql());
	}

	@Test
	void anExplicitModuleAndVersionWin() {
		DuckBinder b = new DuckBinder(SENTINELS, new DuckBinder.Config(1L, "p", null,
				null, "qa", "32506021000036107", List.of(), "20260831"));
		assertEquals("select '32506021000036107', '20260831'",
				b.bind("select 'rvfph_moduleid_', 'rvfph_version_'", "1").sql());
	}

	@Test
	void includedModulesIsNullWhenNoFilterIsConfigured() {
		// The assertions using it branch on `'NULL' = '<INCLUDED_MODULES>'`, so
		// NULL is the documented "no module filter" value - not an empty list.
		assertEquals("where 'NULL' = 'NULL'",
				binder(null, null).bind("where 'NULL' = 'rvfph_included_modules_'", "1").sql());
	}

	@Test
	void includedModulesJoinsOnCommas() {
		DuckBinder b = new DuckBinder(SENTINELS, new DuckBinder.Config(1L, "p", null,
				null, "qa", null, List.of("111", "222"), null));
		assertEquals("in (111,222)", b.bind("in (rvfph_included_modules_)", "1").sql());
		assertEquals("in (111,222)", b.bind("in (rvfph_moduleids_)", "1").sql());
	}

	@Test
	void moduleIdSentinelIsNotCorruptedByTheModuleIdsSentinel() {
		// Both sentinels end in "_" precisely because <MODULEID>'s would
		// otherwise be a prefix of <MODULEIDS>'s. Binding the first must not
		// leave the second half-substituted.
		DuckBinder b = new DuckBinder(SENTINELS, new DuckBinder.Config(1L, "p", null,
				null, "qa", "111", List.of("222"), null));
		assertEquals("one 111 many 222",
				b.bind("one rvfph_moduleid_ many rvfph_moduleids_", "1").sql());
	}

	@Test
	void qaResultIsRewrittenOnWordBoundariesOnly() {
		assertEquals("insert into rvf_results.qa_result select qa_resultant",
				binder(null, null).bind("insert into qa_result select qa_resultant", "1").sql());
	}
}
