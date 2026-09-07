package org.ihtsdo.rvf.core.service.duck;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Merging assertion packs, one refusal at a time.
 *
 * <p>Every case here is a way for two packs to combine into a store that runs
 * and reports the wrong thing, which is the class of failure worth a test: a
 * merge that fails loudly costs a publishing pipeline an afternoon, and a merge
 * that succeeds wrongly costs a validation report its meaning.
 *
 * <p>The fixture is deliberately tiny and hand-written rather than a slice of
 * the real store, so each assertion in this file pins one rule and nothing
 * else. The rules themselves come from the real stores: see
 * {@link DuckStorePacks} and {@code duck/ASSERTION-PACKS.md}.
 */
class DuckStorePacksTest {

	private static final String UUID_A = "11111111-1111-1111-1111-111111111111";
	private static final String UUID_B = "22222222-2222-2222-2222-222222222222";

	/** A pack with one assertion, one port and one prerequisite. */
	private static String pack(String uuid, String file, String statement, String port,
			String prerequisiteHash) {
		return """
				{
				 "formatVersion": 1,
				 "generator": {"sqlglot": "30.15.0", "python": "3.12.3"},
				 "runIdSentinel": "424242424242424242",
				 "qaResultToken": "qa_result",
				 "sentinels": [
				  {"placeholder": "<RUNID>", "sentinel": "424242424242424242"},
				  {"placeholder": "<PROSPECTIVE>", "sentinel": "rvfph_prospective_"}
				 ],
				 "knownTables": ["concept_s"],
				 "tableColumns": {"concept_s": "id BIGINT, active VARCHAR"},
				 "ports": [%s],
				 "prerequisites": [
				  {"file": "pre-requisites.sql", "sha256": "%s",
				   "statements": ["CREATE OR REPLACE TABLE rvfph_prospective_.concept_active AS SELECT * FROM concept_s"]}
				 ],
				 "assertions": {
				  "%s": {"file": "%s", "sha256": "aaaa", "text": "an assertion",
				         "keywords": "component-centric-validation", "severity": "",
				         "statements": ["%s"]}
				 }
				}
				""".formatted(port == null ? "" : "\"" + port + "\"",
				prerequisiteHash, uuid, file, statement);
	}

	private static DuckStorePacks.Pack packOf(String name, String version, String json)
			throws IOException {
		return new DuckStorePacks.Pack(name, version, "sha256:" + name.hashCode(),
				DuckStore.parse(json));
	}

	private static final String MACRO_A =
			"CREATE OR REPLACE MACRO substring_index(s, d, n) AS s";
	private static final String MACRO_A_DIFFERENT =
			"CREATE OR REPLACE MACRO substring_index(s, d, n) AS d";
	private static final String MACRO_B =
			"CREATE OR REPLACE MACRO get_cr_ADRS_PT(c) AS c";

	@Test
	void twoPacksCombineIntoOneStore() throws Exception {
		DuckStore merged = DuckStorePacks.merge(List.of(
				packOf("international", "2026.09.1",
						pack(UUID_A, "a.sql", "INSERT INTO qa_result SELECT 1", MACRO_A, "h1")),
				packOf("amtv4", "2026.09.2",
						pack(UUID_B, "b.sql", "INSERT INTO qa_result SELECT 2", MACRO_B, "h1"))));

		assertEquals(2, merged.assertions().size());
		assertEquals(2, merged.ports().size(), "both macros, neither dropped");
		assertEquals(1, merged.prerequisiteStatements().size(),
				"the same prerequisite file appears once, not twice");
		assertEquals("b.sql", merged.assertions().get(UUID_B).file());
	}

	@Test
	void theMergedStoreIsAWorkingStore() throws Exception {
		// Merging must produce something the ENGINE accepts, not just something
		// that parses: a merged store that DuckAssertionSource cannot read is a
		// store no run can use.
		DuckStore merged = DuckStorePacks.merge(List.of(
				packOf("international", "1", pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
				packOf("amtv4", "1", pack(UUID_B, "b.sql", "SELECT 2", MACRO_B, "h1"))));

		DuckStore reread = DuckStore.parse(merged.toJson());
		assertEquals(2, reread.assertions().size(), "a merged store must survive a round trip");
		assertEquals("qa_result", reread.qaResultToken());
		assertEquals(List.of("concept_s"), reread.knownTables());
		assertEquals("id BIGINT, active VARCHAR", reread.tableColumns().get("concept_s"));
	}

	@Test
	void aRepeatedAssertionIsFineWhenItIsIdentical() throws Exception {
		// Normal, not exceptional: a national pack built from a combined corpus
		// contains the international assertions too, so refusing repetition
		// would refuse the case the design exists for.
		String same = pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1");
		DuckStore merged = DuckStorePacks.merge(List.of(
				packOf("international", "1", same),
				packOf("combined", "1", same)));
		assertEquals(1, merged.assertions().size());
	}

	@Test
	void twoDifferentAssertionsUnderOneUuidAreRefused() throws IOException {
		var conflict = assertThrows(DuckStorePacks.ConflictException.class, () ->
				DuckStorePacks.merge(List.of(
						packOf("international", "1",
								pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
						packOf("rogue", "1",
								pack(UUID_A, "different.sql", "SELECT 99", MACRO_A, "h1")))));
		assertEquals(1, conflict.getConflicts().size());
		assertTrue(conflict.getMessage().contains(UUID_A), conflict.getMessage());
		assertTrue(conflict.getMessage().contains("different.sql"), conflict.getMessage());
	}

	@Test
	void redefiningAnotherPacksMacroIsRefused() throws IOException {
		// THE rule that is not obvious, and the reason ports are keyed by name.
		// Appended instead, this store would execute both definitions in order
		// and the second would win - so an assertion in the first pack would
		// quietly compute something else, and the report would look healthy.
		var conflict = assertThrows(DuckStorePacks.ConflictException.class, () ->
				DuckStorePacks.merge(List.of(
						packOf("international", "1",
								pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
						packOf("rogue", "1",
								pack(UUID_B, "b.sql", "SELECT 2", MACRO_A_DIFFERENT, "h1")))));
		assertTrue(conflict.getMessage().contains("substring_index"), conflict.getMessage());
		assertTrue(conflict.getMessage().contains("redefines"), conflict.getMessage());
	}

	@Test
	void anIdenticalMacroInBothPacksIsNotAConflict() throws Exception {
		// The corollary: every pack published from the same publisher carries
		// the same prelude, so requiring uniqueness would refuse every real
		// combination.
		DuckStore merged = DuckStorePacks.merge(List.of(
				packOf("international", "1", pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
				packOf("amtv4", "1", pack(UUID_B, "b.sql", "SELECT 2", MACRO_A, "h1"))));
		assertEquals(1, merged.ports().size(), "one definition, not two");
	}

	@Test
	void aDifferentTableShapeIsRefused() throws IOException {
		String other = pack(UUID_B, "b.sql", "SELECT 2", MACRO_B, "h1")
				.replace("\"concept_s\": \"id BIGINT, active VARCHAR\"",
						"\"concept_s\": \"id VARCHAR, active VARCHAR\"");
		var conflict = assertThrows(DuckStorePacks.ConflictException.class, () ->
				DuckStorePacks.merge(List.of(
						packOf("international", "1",
								pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
						packOf("rogue", "1", other))));
		assertTrue(conflict.getMessage().contains("concept_s"), conflict.getMessage());
	}

	@Test
	void theSamePrerequisiteFileWithADifferentHashIsRefused() throws IOException {
		var conflict = assertThrows(DuckStorePacks.ConflictException.class, () ->
				DuckStorePacks.merge(List.of(
						packOf("international", "1",
								pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
						packOf("rogue", "1",
								pack(UUID_B, "b.sql", "SELECT 2", MACRO_B, "h2")))));
		assertTrue(conflict.getMessage().contains("pre-requisites.sql"), conflict.getMessage());
	}

	@Test
	void aDifferentSentinelTableIsRefused() throws IOException {
		String other = pack(UUID_B, "b.sql", "SELECT 2", MACRO_B, "h1")
				.replace("rvfph_prospective_", "rvfph_other_");
		var conflict = assertThrows(DuckStorePacks.ConflictException.class, () ->
				DuckStorePacks.merge(List.of(
						packOf("international", "1",
								pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
						packOf("rogue", "1", other))));
		assertTrue(conflict.getMessage().contains("sentinels"), conflict.getMessage());
	}

	@Test
	void aDifferentTranspilerVersionIsRefused() throws IOException {
		String other = pack(UUID_B, "b.sql", "SELECT 2", MACRO_B, "h1")
				.replace("30.15.0", "31.0.0");
		var conflict = assertThrows(DuckStorePacks.ConflictException.class, () ->
				DuckStorePacks.merge(List.of(
						packOf("international", "1",
								pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
						packOf("rogue", "1", other))));
		assertTrue(conflict.getMessage().contains("transpiler"), conflict.getMessage());
	}

	@Test
	void everyConflictIsReportedNotJustTheFirst() throws IOException {
		// A publishing pipeline fixing one conflict per run is a pipeline nobody
		// uses.
		String other = pack(UUID_A, "different.sql", "SELECT 99", MACRO_A_DIFFERENT, "h2")
				.replace("30.15.0", "31.0.0");
		var conflict = assertThrows(DuckStorePacks.ConflictException.class, () ->
				DuckStorePacks.merge(List.of(
						packOf("international", "1",
								pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
						packOf("rogue", "1", other))));
		assertTrue(conflict.getConflicts().size() >= 4,
				"expected transpiler, prerequisite, port and assertion conflicts, got "
						+ conflict.getConflicts());
	}

	@Test
	void theMergedStoreRecordsWhatWentIntoIt() throws Exception {
		// Provenance is the thing packs cost: baked in, the image tag named the
		// corpus; fetched at runtime, only this can answer "which assertions
		// produced this report".
		DuckStore merged = DuckStorePacks.merge(List.of(
				packOf("international", "2026.09.1",
						pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
				packOf("amtv4", "2026.09.2",
						pack(UUID_B, "b.sql", "SELECT 2", MACRO_B, "h1"))));

		assertTrue(merged.toJson().contains("\"name\":\"amtv4\""), merged.toJson());
		assertTrue(merged.toJson().contains("2026.09.1"), merged.toJson());
		assertTrue(merged.toJson().contains("\"digest\""), merged.toJson());
	}

	@Test
	void mergingNothingIsRefused() {
		// An empty store reports every release as clean, which is the one
		// outcome worse than an error.
		assertThrows(IllegalArgumentException.class, () -> DuckStorePacks.merge(List.of()));
	}
}
