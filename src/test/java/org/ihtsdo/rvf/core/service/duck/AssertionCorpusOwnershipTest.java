package org.ihtsdo.rvf.core.service.duck;

import org.ihtsdo.rvf.core.data.model.Assertion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who owns the list {@code findAll()} returns, and who owns the objects in it.
 *
 * <p>Reported by Attila against {@code getAssertionsAndJoinGroups}, which calls
 * {@code addGroup} on the shared corpus objects and then - in
 * {@code GET /assertions?includeDroolsRules=true} - calls {@code addAll} on the
 * list itself. On the MySQL engine both are harmless: {@code findAll()} runs a
 * query and hands back a list nobody else holds, which is why this has survived
 * upstream.
 *
 * <p>On the DuckDB engine the corpus is read once and held in memory, and
 * {@code findAll()} returned the internal list. So a single browse of the
 * assertions page with Drools rules included appended those rules to the
 * corpus that every subsequent VALIDATION enumerates - permanently, and again
 * on every request. The engine has no statements for a Drools rule, so each one
 * would then be reported "No precompiled statements in the DuckDB store ...
 * store and assertion corpus are out of step": a validation degraded by someone
 * opening a web page.
 *
 * <p>The fix is ownership, in three places, and this pins all three.
 */
class AssertionCorpusOwnershipTest {

	private static final String UUID_A = "11111111-1111-1111-1111-111111111111";
	private static final String UUID_B = "22222222-2222-2222-2222-222222222222";

	private DuckAssertionSource sourceIn(Path dir) throws Exception {
		String source = "insert into qa_result select 1;\n";
		Files.createDirectories(dir.resolve("scripts"));
		for (String file : List.of("a.sql", "b.sql")) {
			Files.writeString(dir.resolve("scripts").resolve(file), source);
		}
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
				.substring(0, 16);
		Files.writeString(dir.resolve("store.json"), """
				{
				 "formatVersion": 1,
				 "generator": {"sqlglot": "30.15.0"},
				 "runIdSentinel": "424242424242424242",
				 "qaResultToken": "qa_result",
				 "sentinels": [{"placeholder": "<RUNID>", "sentinel": "424242424242424242"}],
				 "knownTables": [],
				 "tableColumns": {},
				 "ports": [],
				 "prerequisites": [],
				 "assertions": {
				  "%s": {"file": "a.sql", "sha256": "%s", "text": "first",
				         "keywords": "component-centric-validation", "severity": "",
				         "statements": ["INSERT INTO qa_result SELECT 1"]},
				  "%s": {"file": "b.sql", "sha256": "%s", "text": "second",
				         "keywords": "component-centric-validation", "severity": "",
				         "statements": ["INSERT INTO qa_result SELECT 2"]}
				 }
				}
				""".formatted(UUID_A, hash, UUID_B, hash));
		Files.writeString(dir.resolve("groups.xml"), """
				<assertionGroupingStrategy>
				 <group name="component-centric-validation" includeStandaloneCategories="component-centric-validation"/>
				</assertionGroupingStrategy>
				""");
		Files.writeString(dir.resolve("policies.xml"), "<policyValues/>");
		return DuckAssertionSource.from(DuckStore.read(dir.resolve("store.json")), dir);
	}

	@Test
	void findAllHandsBackAMutableCopy(@TempDir Path dir) throws Exception {
		// The contract the OTHER implementation of this interface defines.
		// AssertionServiceImpl.findAll() delegates to a Spring Data repository,
		// so it returns a fresh list every call and its callers append to it.
		//
		// An earlier version of this returned an unmodifiable view instead, on
		// the reasoning that a caller treating the result as its own should fail
		// loudly. It does fail loudly - as an HTTP 500, in production, from a
		// caller that is correct against the only other implementation. Two
		// implementations of one interface differing in mutability IS the defect;
		// the one with the unusual internals should pay to hide them, and the
		// price is a list of a few hundred references per call, here and nowhere
		// else.
		DuckAssertionSource source = sourceIn(dir);
		List<Assertion> assertions = source.findAll();
		assertEquals(2, assertions.size());

		Assertion intruder = new Assertion();
		intruder.setUuid(UUID.randomUUID());
		assertions.add(intruder);
		assertEquals(3, assertions.size(), "the caller owns what it was given");
		assertEquals(2, source.findAll().size(), "and the corpus is unchanged");
	}

	@Test
	void joiningGroupsOnSharedAssertionsIsSafeAndIdempotent(@TempDir Path dir) throws Exception {
		// The other half, and the reason the list copy alone is not enough: the
		// OBJECTS are shared, and upstream's join calls addGroup on them
		// unconditionally. Against an immutable group set that throws; against a
		// plain HashSet two concurrent requests race for no gain. The corpus
		// hands out concurrent sets, so the caller needs to know nothing.
		DuckAssertionSource source = sourceIn(dir);
		Assertion assertion = source.findAll().get(0);
		int before = assertion.getGroups().size();

		assertion.addGroup("component-centric-validation");   // already present
		assertion.addGroup("a-group-it-did-not-have");

		assertEquals(before + 1, assertion.getGroups().size(),
				"re-adding an existing group changes nothing, and a new one is accepted");
		assertTrue(source.findAll().get(0).getGroups().contains("a-group-it-did-not-have"),
				"the objects are shared, which is what makes the set's thread safety matter");
	}

	@Test
	void aCallerThatWantsToAddCanCopy(@TempDir Path dir) throws Exception {
		// What the endpoint should do, and the reason an unmodifiable view is
		// enough: the fix costs one allocation of a 360-element list per
		// request.
		DuckAssertionSource source = sourceIn(dir);
		List<Assertion> mine = new ArrayList<>(source.findAll());
		Assertion droolsRule = new Assertion();
		droolsRule.setUuid(UUID.randomUUID());
		mine.add(droolsRule);

		assertEquals(3, mine.size());
		assertEquals(2, source.findAll().size(), "the corpus is not the caller's list");
	}

	@Test
	void theCorpusCarriesItsGroupsWithoutBeingToldThem(@TempDir Path dir) throws Exception {
		// The other half of the report: getAssertionsAndJoinGroups calls
		// addGroup on shared objects. It is idempotent, so it is survivable -
		// but only because the groups are ALREADY right. The store-backed
		// source resolves group membership when it loads, so it sets it on the
		// model there and no caller has to mutate anything to get a correct
		// answer from GET /assertions.
		DuckAssertionSource source = sourceIn(dir);
		for (Assertion assertion : source.findAll()) {
			assertTrue(assertion.getGroups() != null
							&& assertion.getGroups().contains("component-centric-validation"),
					assertion.getUuid() + " should carry its group: " + assertion.getGroups());
		}
	}

	@Test
	void aSecondBrowseDoesNotGrowTheCorpus(@TempDir Path dir) throws Exception {
		// The reported failure, in the shape it actually reached a user: the
		// assertions page requested twice. Before the fix the second call saw a
		// corpus one Drools rule larger than the first, and a validation
		// afterwards saw both.
		DuckAssertionSource source = sourceIn(dir);
		for (int browse = 0; browse < 2; browse++) {
			List<Assertion> forThisRequest = new ArrayList<>(source.findAll());
			Assertion droolsRule = new Assertion();
			droolsRule.setUuid(UUID.randomUUID());
			forThisRequest.add(droolsRule);
			assertEquals(3, forThisRequest.size());
		}
		assertEquals(2, source.findAll().size(), "two browses, corpus still the corpus");
	}
}
