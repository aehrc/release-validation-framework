package org.ihtsdo.rvf.core.service.duck;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fetching a pack over HTTP, verifying its digest, and swapping the corpus -
 * or not, in which case the old corpus must still be serving.
 *
 * <p>A real {@link HttpServer} rather than a mocked client, because the things
 * that go wrong here are the ones a mock is least able to show: a body that
 * arrives but is not what was pinned, a 404 from a repository that moved a
 * release asset, a pack that parses but cannot be merged. All three are
 * exercised against a socket.
 *
 * <p>What matters most is the invariant after a failure. A pack is executable
 * SQL fetched over a network, and the failure mode to design against is not an
 * exception - it is an engine that ends up serving a half-applied assertion
 * set, because that reports a release as clean for assertions it no longer
 * holds.
 */
class AssertionPackReloadTest {

	private static final String UUID_BASE = "11111111-1111-1111-1111-111111111111";
	private static final String UUID_PACK = "22222222-2222-2222-2222-222222222222";

	private HttpServer server;
	private byte[] served = new byte[0];
	private int status = 200;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/pack.json", exchange -> {
			exchange.sendResponseHeaders(status, status == 200 ? served.length : -1);
			if (status == 200) {
				try (OutputStream out = exchange.getResponseBody()) {
					out.write(served);
				}
			}
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	private URI uri() {
		return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/pack.json");
	}

	private void serve(String body) {
		served = body.getBytes(StandardCharsets.UTF_8);
	}

	private static String sha256(String s) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(s.getBytes(StandardCharsets.UTF_8)));
	}

	/**
	 * A store with one assertion in a group the corpus below declares.
	 *
	 * <p>The declared {@code sha256} is the real prefix of the .sql this test
	 * writes into the corpus, because {@link DuckStoreLocator} verifies every
	 * assertion's recorded hash against the corpus file of the same name and
	 * refuses a store published from a different one. Faking it here would test
	 * a path production does not have.
	 */
	private static String store(String uuid, String file, String macro) {
		return """
				{
				 "formatVersion": 1,
				 "generator": {"sqlglot": "30.15.0"},
				 "runIdSentinel": "424242424242424242",
				 "qaResultToken": "qa_result",
				 "sentinels": [{"placeholder": "<RUNID>", "sentinel": "424242424242424242"}],
				 "knownTables": ["concept_s"],
				 "tableColumns": {"concept_s": "id BIGINT"},
				 "ports": ["%s"],
				 "prerequisites": [],
				 "assertions": {
				  "%s": {"file": "%s", "sha256": "%s", "text": "%s",
				         "keywords": "component-centric-validation", "severity": "",
				         "statements": ["INSERT INTO qa_result SELECT 1"]}
				 }
				}
				""".formatted(macro, uuid, file, sourceHash(file), file);
	}

	/** The 16-char sha256 prefix of the .sql body this test writes for a file. */
	private static String sourceHash(String file) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(sourceOf(file).getBytes(StandardCharsets.UTF_8))).substring(0, 16);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static String sourceOf(String file) {
		return "-- " + file + "\ninsert into qa_result select 1;\n";
	}

	private static final String MACRO = "CREATE OR REPLACE MACRO m(x) AS x";
	private static final String MACRO_CONFLICTING = "CREATE OR REPLACE MACRO m(x) AS 1";

	/** The bundled store plus the groups/policies the source needs. */
	private DuckAssertionService serviceWith(Path dir, String packSpec) throws Exception {
		Files.createDirectories(dir.resolve("scripts"));
		for (String file : List.of("base.sql", "pack.sql", "something-else.sql")) {
			Files.writeString(dir.resolve("scripts").resolve(file), sourceOf(file));
		}
		Files.writeString(dir.resolve("store.json"), store(UUID_BASE, "base.sql", MACRO));
		Files.writeString(dir.resolve("groups.xml"), """
				<assertionGroupingStrategy>
				 <group name="component-centric-validation" includeStandaloneCategories="component-centric-validation"/>
				</assertionGroupingStrategy>
				""");
		Files.writeString(dir.resolve("policies.xml"), "<policyValues/>");
		DuckStoreLocator locator = new DuckStoreLocator(
				dir.resolve("store.json").toString(), dir.toString());
		return new DuckAssertionService(locator, dir.toString(),
				packSpec == null ? List.of() : List.of(packSpec));
	}

	private String spec(String digest) {
		return "name=amtv4;version=2026.09.1;uri=" + uri() + ";sha256=" + digest;
	}

	@Test
	void aVerifiedPackIsMergedAndSwappedIn(@TempDir Path dir) throws Exception {
		String pack = store(UUID_PACK, "pack.sql", MACRO);
		serve(pack);
		DuckAssertionService service = serviceWith(dir, spec(sha256(pack)));

		assertEquals(1, service.findAll().size(), "the bundled store alone, before the swap");

		String description = service.refreshConfiguredPacks();

		assertEquals(2, service.findAll().size(), "base plus pack");
		assertTrue(description.contains("amtv4@2026.09.1"), description);
		assertEquals(1, service.loadedPacks().size());
		assertEquals("amtv4", service.loadedPacks().get(0).name());
		assertTrue(service.loadedPacks().get(0).digest().startsWith("sha256:"));
		assertTrue(service.storedAssertion(UUID_PACK).isPresent(),
				"the pack's statements are what the engine would now execute");
	}

	@Test
	void aBodyThatIsNotWhatWasPinnedIsRefusedAndNothingChanges(@TempDir Path dir)
			throws Exception {
		String pack = store(UUID_PACK, "pack.sql", MACRO);
		serve(pack);
		// Pin the digest of a DIFFERENT pack: the fetch succeeds, the bytes are
		// well-formed and parseable, and they are still not what was approved.
		String wrong = sha256(store(UUID_PACK, "something-else.sql", MACRO));
		DuckAssertionService service = serviceWith(dir, spec(wrong));

		IOException e = assertThrows(IOException.class, service::refreshConfiguredPacks);
		assertTrue(e.getMessage().contains("pinned"), e.getMessage());
		assertEquals(1, service.findAll().size(), "the bundled corpus is still serving");
		assertTrue(service.loadedPacks().isEmpty());
	}

	@Test
	void anUnreachablePackLeavesTheCorpusAlone(@TempDir Path dir) throws Exception {
		String pack = store(UUID_PACK, "pack.sql", MACRO);
		serve(pack);
		DuckAssertionService service = serviceWith(dir, spec(sha256(pack)));
		assertEquals(2, service.refreshConfiguredPacks().isEmpty() ? 0 : service.findAll().size());

		// The release asset moves, as they do.
		status = 404;
		IOException e = assertThrows(IOException.class, service::refreshConfiguredPacks);
		assertTrue(e.getMessage().contains("404"), e.getMessage());
		assertEquals(2, service.findAll().size(),
				"the corpus from the last good refresh keeps serving");
		assertEquals(1, service.loadedPacks().size());
	}

	@Test
	void aPackThatCannotBeMergedIsRefusedAndNothingChanges(@TempDir Path dir) throws Exception {
		// Parses, verifies, and redefines the bundled store's macro - the
		// conflict that would otherwise change the meaning of the base pack's
		// SQL.
		String pack = store(UUID_PACK, "pack.sql", MACRO_CONFLICTING);
		serve(pack);
		DuckAssertionService service = serviceWith(dir, spec(sha256(pack)));

		var conflict = assertThrows(DuckStorePacks.ConflictException.class,
				service::refreshConfiguredPacks);
		assertTrue(conflict.getMessage().contains("redefines"), conflict.getMessage());
		assertEquals(1, service.findAll().size(), "the bundled corpus is untouched");
		assertTrue(service.loadedPacks().isEmpty());
	}

	@Test
	void anUnpinnedPackIsRejectedByConfigurationNotAtFetchTime(@TempDir Path dir)
			throws Exception {
		// No sha256 in the spec. Refused when the source is built, so a
		// deployment cannot start with an unpinnable pack configured and find
		// out on the first refresh.
		DuckAssertionService service = serviceWith(dir,
				"name=amtv4;version=1;uri=" + uri());
		IllegalArgumentException e =
				assertThrows(IllegalArgumentException.class, service::configuredPackSources);
		assertTrue(e.getMessage().contains("pinned"), e.getMessage());
	}

	@Test
	void noConfiguredPacksMeansTheBundledStoreAndThatIsNotAnError(@TempDir Path dir)
			throws Exception {
		// Every current deployment. A refresh with nothing configured must
		// reload the bundled store rather than empty the corpus.
		DuckAssertionService service = serviceWith(dir, null);
		String description = service.refreshConfiguredPacks();
		assertTrue(description.contains("bundled store only"), description);
		assertEquals(1, service.findAll().size());
	}

	@Test
	void aFilePackWorksBecauseThatIsHowTheVolumeLayoutFeedsIt(@TempDir Path dir)
			throws Exception {
		String pack = store(UUID_PACK, "pack.sql", MACRO);
		Path packFile = dir.resolve("amtv4-pack.json");
		Files.writeString(packFile, pack);
		DuckAssertionService service = serviceWith(dir,
				"name=amtv4;version=1;uri=" + packFile.toUri() + ";sha256=" + sha256(pack));

		service.refreshConfiguredPacks();
		assertEquals(2, service.findAll().size());
	}
}
