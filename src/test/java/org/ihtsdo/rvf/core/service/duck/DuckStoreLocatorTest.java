package org.ihtsdo.rvf.core.service.duck;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DuckStoreLocatorTest {

	private static final String ONE = "select 1 from concept_s;\n";
	private static final String TWO = "select 2 from description_s;\n";

	@TempDir
	Path dir;

	@Test
	void loadsTheBundledStoreWhenNothingIsConfigured() throws IOException {
		// The point of bundling: a built artefact has a corpus with no
		// configuration at all. If this fails the resource did not make it into
		// the build, and every deployment would need rvf.duck.store set.
		DuckStore store = new DuckStoreLocator("", "").load();
		assertNotNull(store);
		assertTrue(store.assertions().size() > 300,
				"bundled store looks truncated: " + store.assertions().size() + " assertions");
	}

	@Test
	void aConfiguredStoreWinsOverTheBundledOne() throws IOException {
		// Overriding without a rebuild is how a different corpus gets tried.
		Path store = writeStore(sha(ONE), sha(TWO));
		DuckStore loaded = new DuckStoreLocator(store.toString(), "").load();
		assertEquals(2, loaded.assertions().size());
	}

	@Test
	void aStorePublishedFromThisCorpusVerifies() throws IOException {
		Path corpus = corpusWith(ONE, TWO);
		Path store = writeStore(sha(ONE), sha(TWO));
		assertEquals(2, new DuckStoreLocator(store.toString(), corpus.toString())
				.load().assertions().size());
	}

	@Test
	void aChangedCorpusScriptIsFatalAndNamed() throws IOException {
		// The failure this whole class exists for: the corpus moved, the store
		// did not. Silent otherwise - the run would execute the store's old SQL
		// and report it under the new corpus's assertion text.
		Path corpus = corpusWith(ONE + "-- edited\n", TWO);
		Path store = writeStore(sha(ONE), sha(TWO));
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> new DuckStoreLocator(store.toString(), corpus.toString()).load());
		assertTrue(e.getMessage().contains("one.sql"), e.getMessage());
		assertTrue(e.getMessage().contains("1 of 2 assertions differ"), e.getMessage());
		// It must say what to do, not only what is wrong.
		assertTrue(e.getMessage().contains("Republish"), e.getMessage());
	}

	@Test
	void aScriptTheCorpusNoLongerShipsIsFatal() throws IOException {
		Path corpus = corpusWith(ONE, null);
		Path store = writeStore(sha(ONE), sha(TWO));
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> new DuckStoreLocator(store.toString(), corpus.toString()).load());
		assertTrue(e.getMessage().contains("two.sql"), e.getMessage());
		assertTrue(e.getMessage().contains("1 are absent"), e.getMessage());
	}

	@Test
	void noCorpusMeansUnverifiedNotRejected() throws IOException {
		// A store-only deployment and every unit test hit this. Refusing here
		// would make the corpus a hard dependency of running at all, which it is
		// not - the store carries everything a run executes.
		Path store = writeStore(sha(ONE), sha(TWO));
		assertEquals(2, new DuckStoreLocator(store.toString(), "").load().assertions().size());
		assertEquals(2, new DuckStoreLocator(store.toString(), dir.resolve("absent").toString())
				.load().assertions().size());
	}

	@Test
	void twoCorpusScriptsWithOneNameAreRejectedRatherThanPickedBetween() throws IOException {
		// The store identifies assertions by base name. If the corpus ever grew
		// a duplicate, comparing against an arbitrary one of the two would make
		// this check pass or fail depending on directory order.
		Path corpus = corpusWith(ONE, TWO);
		Path nested = Files.createDirectories(corpus.resolve("release-type"));
		Files.writeString(nested.resolve("one.sql"), "select 99;\n");
		Path store = writeStore(sha(ONE), sha(TWO));
		IllegalStateException e = assertThrows(IllegalStateException.class,
				() -> new DuckStoreLocator(store.toString(), corpus.toString()).load());
		assertTrue(e.getMessage().contains("share the name"), e.getMessage());
	}

	@Test
	void aStoreWithoutRecordedHashesIsReadableButUnverified() throws IOException {
		// Refusing a store published before the publisher recorded hashes would
		// be worse than saying so: it is readable and internally consistent.
		Path corpus = corpusWith(ONE, TWO);
		Path store = dir.resolve("nohash.json");
		Files.writeString(store, """
				{"formatVersion": 1,
				 "assertions": {"a": {"file": "one.sql", "statements": ["select 1"]}}}
				""");
		assertEquals(1, new DuckStoreLocator(store.toString(), corpus.toString())
				.load().assertions().size());
	}

	@Test
	void describesWhereTheStoreCameFrom() {
		assertEquals("bundled /duck/store.json", new DuckStoreLocator("", "").description());
		assertEquals("/x/store.json", new DuckStoreLocator("/x/store.json", "").description());
		assertEquals("bundled /duck/store.json", new DuckStoreLocator("   ", "").description());
	}

	/** A corpus laid out as the real one is: scripts under category directories. */
	private Path corpusWith(String one, String two) throws IOException {
		Path corpus = Files.createDirectories(dir.resolve("corpus"));
		Path scripts = Files.createDirectories(corpus.resolve("scripts").resolve("component-centric"));
		if (one != null) {
			Files.writeString(scripts.resolve("one.sql"), one);
		}
		if (two != null) {
			Files.writeString(scripts.resolve("two.sql"), two);
		}
		return corpus;
	}

	private Path writeStore(String shaOne, String shaTwo) throws IOException {
		Path store = dir.resolve("store.json");
		Files.writeString(store, """
				{"formatVersion": 1,
				 "assertions": {
				  "aaa-1": {"file": "one.sql", "sha256": "%s", "statements": ["select 1"]},
				  "bbb-2": {"file": "two.sql", "sha256": "%s", "statements": ["select 2"]}
				 }}
				""".formatted(shaOne, shaTwo));
		return store;
	}

	/** The publisher's hash: sha256 of the text, first 16 hex characters. */
	private static String sha(String text) {
		try {
			byte[] hash = MessageDigest.getInstance("SHA-256")
					.digest(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(hash).substring(0, 16);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}
}
