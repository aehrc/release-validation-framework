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
	private static final String UUID_C = "33333333-3333-3333-3333-333333333333";

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
		// produced this report". It lives INSIDE the artefact so a report can
		// read it from the store that ran, rather than from a service holding a
		// second copy of the answer.
		DuckStore merged = DuckStorePacks.merge(List.of(
				packOf("international", "2026.09.1",
						pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
				packOf("amtv4", "2026.09.2",
						pack(UUID_B, "b.sql", "SELECT 2", MACRO_B, "h1"))));

		List<DuckStore.PackRecord> packs = merged.packs();
		assertEquals(2, packs.size());
		assertEquals("international", packs.get(0).name());
		assertEquals("2026.09.2", packs.get(1).version());
		assertEquals(1, packs.get(1).assertions());
		assertTrue(packs.get(1).digest().startsWith("sha256:"), packs.get(1).digest());

		// And it survives a round trip, because the report is written from a
		// store that was read back from disk on the worker.
		assertEquals(2, DuckStore.parse(merged.toJson()).packs().size());
	}

	@Test
	void anUnmergedStoreHasNoPacks() throws Exception {
		// Every deployment today. The field has to be empty rather than absent
		// or invented, so a report from the bundled store says so plainly.
		assertTrue(DuckStore.parse(pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1"))
				.packs().isEmpty());
	}

	/** A store carrying its own identity, with the digest the publisher computes. */
	private static String identified(String name, String version, String uuid,
			String sourceHash) {
		String json = pack(uuid, "a.sql", "SELECT 1", MACRO_A, "h1")
				.replace("\"sha256\": \"aaaa\"", "\"sha256\": \"" + sourceHash + "\"");
		String digest = digestOf(uuid, sourceHash);
		return json.replace(" \"assertions\": {",
				" \"pack\": {\"name\": \"" + name + "\", \"version\": \"" + version
						+ "\", \"corpusRef\": \"0160dd2e\", \"digest\": \"" + digest
						+ "\", \"assertions\": 1},\n \"assertions\": {");
	}

	/**
	 * The publisher's digest, computed independently of the code under test.
	 *
	 * <p>Spelled out rather than delegated to {@code assertionDigest()}: a test
	 * that asks the implementation what the answer is cannot notice the
	 * implementation changing its mind. The material covers the prerequisites
	 * too, because they build the tables every assertion reads.
	 */
	private static String digestOf(String uuid, String sourceHash) {
		try {
			java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
			String material = uuid + "\t" + sourceHash + "\n--\npre-requisites.sql\th1";
			return "sha256:" + java.util.HexFormat.of().formatHex(sha.digest(
					material.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	@Test
	void aChangedPrerequisiteBreaksTheDigestToo() throws Exception {
		// pre-requisites.sql builds the tables every assertion reads, so a store
		// with a changed one runs differently while its assertion set is
		// untouched. A digest blind to that cannot answer "would this reproduce
		// the old report".
		String tampered = identified("international", "2026.07.27", UUID_A, "aaaa")
				.replace("\"sha256\": \"h1\"", "\"sha256\": \"h2\"");
		IOException e = assertThrows(IOException.class,
				() -> DuckStore.parse(tampered).identity());
		assertTrue(e.getMessage().contains("declares digest"), e.getMessage());
	}


	/** A pack that declares what it needs of another. */
	private static String requiring(String uuid, String file, String requires) {
		return pack(uuid, file, "SELECT 1", MACRO_B, "h1")
				.replace(" \"assertions\": {",
						" \"requires\": [" + requires + "],\n \"assertions\": {");
	}

	@Test
	void aSatisfiedRequirementMergesSilently() throws Exception {
		DuckStore merged = DuckStorePacks.merge(List.of(
				packOf("international", "2026.07.27",
						pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
				packOf("amtv4", "2026.09.1", requiring(UUID_B, "b.sql",
						"{\"pack\": \"international\", \"atLeast\": \"2026.07.01\"}"))));
		assertEquals(2, merged.assertions().size());
	}

	@Test
	void anOlderBaseIsRefusedNamingBothVersions() throws Exception {
		// The failure this prevents happened for real: the publisher regression
		// dropped substring_index and four assertions died at RUN time. The pack
		// fetched cleanly, matched its digest and merged without conflict.
		DuckStorePacks.ConflictException e = assertThrows(
				DuckStorePacks.ConflictException.class,
				() -> DuckStorePacks.merge(List.of(
						packOf("international", "2026.06.01",
								pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
						packOf("amtv4", "2026.09.1", requiring(UUID_B, "b.sql",
								"{\"pack\": \"international\", \"atLeast\": \"2026.07.27\"}")))));
		String message = String.join("\n", e.getConflicts());
		assertTrue(message.contains("amtv4@2026.09.1"), message);
		assertTrue(message.contains("international at least 2026.07.27"), message);
		assertTrue(message.contains("international@2026.06.01"), message);
	}

	@Test
	void anEqualVersionSatisfiesAtLeast() throws Exception {
		assertEquals(2, DuckStorePacks.merge(List.of(
				packOf("international", "2026.07.27",
						pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
				packOf("amtv4", "2026.09.1", requiring(UUID_B, "b.sql",
						"{\"pack\": \"international\", \"atLeast\": \"2026.07.27\"}"))))
				.assertions().size());
	}

	@Test
	void aVersionThatIsNotADateIsRefusedRatherThanSorted() throws Exception {
		// A lexicographic compare orders YYYY.MM.DD and silently mis-orders
		// anything else - 2026.9.1 above 2026.10.1 - so "it happens to sort" is
		// not a comparison.
		DuckStorePacks.ConflictException e = assertThrows(
				DuckStorePacks.ConflictException.class,
				() -> DuckStorePacks.merge(List.of(
						packOf("international", "v2",
								pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
						packOf("amtv4", "2026.09.1", requiring(UUID_B, "b.sql",
								"{\"pack\": \"international\", \"atLeast\": \"2026.07.27\"}")))));
		assertTrue(String.join("", e.getConflicts()).contains("cannot be checked"),
				e.getMessage());
	}

	@Test
	void aRequirementOnAPackNotInTheMergeIsRefused() throws Exception {
		DuckStorePacks.ConflictException e = assertThrows(
				DuckStorePacks.ConflictException.class,
				() -> DuckStorePacks.merge(List.of(
						packOf("international", "2026.07.27",
								pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
						packOf("amtv4", "2026.09.1", requiring(UUID_B, "b.sql",
								"{\"pack\": \"nz-edition\", \"atLeast\": \"2026.01.01\"}")))));
		String message = String.join("", e.getConflicts());
		assertTrue(message.contains("no pack called nz-edition"), message);
		assertTrue(message.contains("international"), "names what IS here: " + message);
	}

	@Test
	void anExactDigestRequirementIsCheckedToo() throws Exception {
		DuckStorePacks.ConflictException e = assertThrows(
				DuckStorePacks.ConflictException.class,
				() -> DuckStorePacks.merge(List.of(
						packOf("international", "2026.07.27",
								pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
						packOf("amtv4", "2026.09.1", requiring(UUID_B, "b.sql",
								"{\"pack\": \"international\", \"digest\": \"sha256:nope\"}")))));
		assertTrue(String.join("", e.getConflicts()).contains("nobody validated"),
				e.getMessage());
	}

	@Test
	void aRequirementThisRuntimeCannotUnderstandIsRefusedNotIgnored() throws Exception {
		// An ignored requirement is worse than no requirement: it reads as a
		// checked combination and is an unchecked one.
		IOException e = assertThrows(IOException.class,
				() -> DuckStore.parse(requiring(UUID_B, "b.sql",
						"{\"pack\": \"international\", \"atMost\": \"2026.09.01\"}"))
						.requirements());
		assertTrue(e.getMessage().contains("atMost"), e.getMessage());
		assertTrue(e.getMessage().contains("does not understand"), e.getMessage());
	}

	@Test
	void aRequirementMustSayExactlyOneOfAtLeastOrDigest() throws Exception {
		assertTrue(assertThrows(IOException.class,
				() -> DuckStore.parse(requiring(UUID_B, "b.sql",
						"{\"pack\": \"international\"}")).requirements())
				.getMessage().contains("neither"));
		assertTrue(assertThrows(IOException.class,
				() -> DuckStore.parse(requiring(UUID_B, "b.sql",
						"{\"pack\": \"international\", \"atLeast\": \"2026.01.01\","
								+ " \"digest\": \"sha256:x\"}")).requirements())
				.getMessage().contains("both"));
	}

	@Test
	void aMergedStoreKeepsEveryInputsRequirementsAndCanSatisfyThem() throws Exception {
		// A merged store is a legitimate base for a later merge. Two things have
		// to hold: the requirements the packs on top declared must survive - the
		// merged JSON starts as the BASE's, so they are easy to drop - and they
		// must still be satisfiable, since the merged store arrives as ONE pack
		// under one name while containing several.
		DuckStore merged = DuckStorePacks.merge(List.of(
				packOf("international", "2026.07.27",
						pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1")),
				packOf("amtv4", "2026.09.1", requiring(UUID_B, "b.sql",
						"{\"pack\": \"international\", \"atLeast\": \"2026.07.01\"}"))));

		assertEquals(1, merged.requirements().size(), "the pack's requirement survived");
		assertEquals("international", merged.requirements().get(0).pack());

		DuckStore again = DuckStorePacks.merge(List.of(
				new DuckStorePacks.Pack("au-combined", "2026.09.2",
						"sha256:combined", merged),
				packOf("nz-edition", "2026.09.3",
						pack(UUID_C, "c.sql", "SELECT 3", null, "h1"))));
		assertEquals(3, again.assertions().size(),
				"the carried requirement is satisfied by the merged store's own packs");
	}

	@Test
	void anUnmergedStoreStatesItsOwnIdentity() throws Exception {
		// The gap this closes: an unmerged store - every deployment that pins no
		// packs - answered "no packs", so a report and GET /assertions/packs said
		// nothing at all about the assertions that ran, on the normal case.
		DuckStore store = DuckStore.parse(identified("international", "2026.07.27",
				UUID_A, "aaaa"));
		List<DuckStore.PackRecord> provenance = store.provenance();
		assertEquals(1, provenance.size());
		assertEquals("international", provenance.get(0).name());
		assertEquals("2026.07.27", provenance.get(0).version());
		assertEquals(digestOf(UUID_A, "aaaa"), provenance.get(0).digest());
		assertEquals(1, provenance.get(0).assertions());
	}

	@Test
	void anEditedStoreIsRefusedRatherThanBelieved() throws Exception {
		// A digest a runtime only repeats is a claim. This one is recomputed, so
		// a store edited after publication cannot describe itself as the store
		// that was published - which is the whole value of recording it.
		String tampered = identified("international", "2026.07.27", UUID_A, "aaaa")
				.replace("\"sha256\": \"aaaa\"", "\"sha256\": \"bbbb\"");
		IOException e = assertThrows(IOException.class,
				() -> DuckStore.parse(tampered).identity());
		assertTrue(e.getMessage().contains("declares digest"), e.getMessage());
		assertTrue(e.getMessage().contains("international@2026.07.27"), e.getMessage());
	}

	@Test
	void aStorePublishedBeforeIdentitiesIsReadableAndSaysNothing() throws Exception {
		// Refusing it would make an old artefact unrunnable to gain a label.
		DuckStore store = DuckStore.parse(pack(UUID_A, "a.sql", "SELECT 1", MACRO_A, "h1"));
		assertTrue(store.identity().isEmpty());
		assertTrue(store.provenance().isEmpty());
	}

	@Test
	void aMergedStoreReportsItsPacksRatherThanTheBaseIdentity() throws Exception {
		// provenance() must not prefer the base's own identity once a merge has
		// happened: the merged list is the complete answer and the base is one
		// entry in it.
		DuckStore merged = DuckStorePacks.merge(List.of(
				packOf("international", "2026.07.27",
						identified("international", "2026.07.27", UUID_A, "aaaa")),
				packOf("amtv4", "2026.09.1",
						pack(UUID_B, "b.sql", "SELECT 2", MACRO_B, "h1"))));
		assertEquals(List.of("international", "amtv4"),
				merged.provenance().stream().map(DuckStore.PackRecord::name).toList());
	}

	@Test
	void mergingNothingIsRefused() {
		// An empty store reports every release as clean, which is the one
		// outcome worse than an error.
		assertThrows(IllegalArgumentException.class, () -> DuckStorePacks.merge(List.of()));
	}
}
