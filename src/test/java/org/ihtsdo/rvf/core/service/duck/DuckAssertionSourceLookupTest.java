package org.ihtsdo.rvf.core.service.duck;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The source lookup behind {@code GET /assertions/{uuid}/source}.
 *
 * <p>A report names an assertion and counts its failures but cannot say what it
 * checked. These pin the two answers that matter: the statements returned are
 * the ones the engine EXECUTES, and an assertion with no SQL says so rather than
 * looking unknown.
 */
class DuckAssertionSourceLookupTest {

	/** Real uuids: DuckAssertionSource parses them, so "u-1" will not do. */
	private static final String PRESENT = "11111111-2222-3333-4444-555555555555";
	private static final String ABSENT = "99999999-8888-7777-6666-555555555555";

	@TempDir
	Path temp;

	private DuckAssertionService serviceFor(String uuid) throws Exception {
		Path corpus = temp.resolve("corpus");
		Files.createDirectories(corpus);
		Files.createDirectories(corpus.resolve("scripts"));

		// The store records each assertion's source hash and DuckStoreLocator
		// REFUSES a store whose hashes do not match the corpus - the check that
		// stops a store being executed while findings are reported under a
		// different corpus's uuids. So the fixture has to be internally
		// consistent: write the script, then hash what was written.
		Path script = corpus.resolve("scripts").resolve("a-check.sql");
		Files.writeString(script, "SELECT 1;\n");
		String sha = sha256(script);

		Path store = temp.resolve("store.json");
		Files.writeString(store, store(uuid, sha));
		// Grouping files are read from the corpus; the store carries the SQL.
		Files.writeString(corpus.resolve("groups.xml"),
				"<assertionGroupingStrategy>"
						+ "<group name=\"g1\" includeStandaloneCategories=\"cat\" />"
						+ "</assertionGroupingStrategy>");
		Files.writeString(corpus.resolve("policies.xml"), "<assertionPolicies/>");
		DuckStoreLocator locator = new DuckStoreLocator(store.toString(), corpus.toString());
		return new DuckAssertionService(locator, corpus.toString(), java.util.List.of());
	}

	private static String sha256(Path file) throws Exception {
		java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
		byte[] hash = digest.digest(Files.readAllBytes(file));
		StringBuilder hex = new StringBuilder();
		for (byte b : hash) {
			hex.append(String.format("%02x", b));
		}
		return hex.toString();
	}

	private static String store(String uuid, String sha) {
		return """
				{
				  "formatVersion": 1,
				  "sentinels": {},
				  "knownTables": [],
				  "tableColumns": {},
				  "ports": [],
				  "assertions": {
				    "%s": {
				      "file": "a-check.sql",
				      "text": "A check that something holds",
				      "keywords": "cat",
				      "severity": "",
				      "sha256": "%s",
				      "statements": ["INSERT INTO qa_result SELECT 1", "SELECT 2"]
				    }
				  }
				}
				""".formatted(uuid, sha);
	}

	@Test
	void returnsTheStatementsTheEngineWouldExecute() throws Exception {
		DuckAssertionService service = serviceFor(PRESENT);

		Optional<DuckStore.StoredAssertion> found = service.storedAssertion(PRESENT);

		assertTrue(found.isPresent(), "the store carries this assertion");
		DuckStore.StoredAssertion stored = found.orElseThrow();
		assertEquals("a-check.sql", stored.file());
		assertEquals("A check that something holds", stored.text());
		assertEquals(2, stored.statements().size(),
				"both statements must be returned - an assertion is not always one query");
		assertTrue(stored.statements().getFirst().contains("qa_result"),
				"the statements are the transpiled ones the engine runs");
	}

	@Test
	void anAssertionWithNoSqlIsAbsentRatherThanEmpty() throws Exception {
		DuckAssertionService service = serviceFor(PRESENT);

		// A Drools rule or MRCM check: real in a report, never in the store.
		Optional<DuckStore.StoredAssertion> found = service.storedAssertion(ABSENT);

		assertFalse(found.isPresent(),
				"absent, so the endpoint can say WHY rather than return an empty body");
	}
}
