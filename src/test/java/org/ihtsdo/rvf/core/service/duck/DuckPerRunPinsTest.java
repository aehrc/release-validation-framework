package org.ihtsdo.rvf.core.service.duck;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;

import org.ihtsdo.rvf.core.data.model.ValidationReport;
import org.ihtsdo.rvf.core.data.model.TestRunItem;
import org.ihtsdo.rvf.core.service.FailureArchiveCollector;
import org.ihtsdo.rvf.core.service.ReleaseAcquisitionService;
import org.ihtsdo.rvf.core.service.ValidationReportService;
import org.ihtsdo.rvf.core.service.WhitelistService;
import org.ihtsdo.rvf.core.service.config.MysqlExecutionConfig;
import org.ihtsdo.rvf.core.service.config.ValidationRunConfig;
import org.ihtsdo.rvf.core.service.pojo.ValidationStatusReport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A run can pin the assertion packs it executes, so an old report can be
 * reproduced after the deployment's own pins have moved on.
 *
 * <p>WHAT MAKES THIS MORE THAN A LABEL: naming the packs in a report says which
 * assertions produced it, but reproducing it needs them back. The deployment's
 * pins are a values file someone edits; a run that recorded
 * {@code amtv4 2026.09.1} can only be re-run if it can ask for that pack rather
 * than for whatever is loaded today.
 *
 * <p>Each run is a full validation against the same release, so the check is
 * the one that matters: the reports name different packs AND report different
 * findings, rather than the pins being accepted and ignored.
 *
 * <p>A run pins {@code name@version} only, resolved through a configured
 * channel: anything a caller could use to point the server at a location of its
 * own - a full spec, {@code file:}, a credential - is refused.
 */
class DuckPerRunPinsTest {

	private static final String UUID_BASE = "11111111-1111-1111-1111-111111111111";
	private static final String UUID_PACK_ONE = "22222222-2222-2222-2222-222222222222";
	private static final String UUID_PACK_TWO = "33333333-3333-3333-3333-333333333333";

	/** Pack name and version, each served from {@code /<file>}. */
	private static final List<String[]> OFFERED = List.of(
			new String[] {"packOne", "2026.09.1", "pack-one.sql"},
			new String[] {"packTwo", "2026.09.2", "pack-two.sql"});

	/** One assertion per store, counting rows in the prospective release. */
	private static String store(String uuid, String file) {
		return """
				{
				 "formatVersion": 1,
				 "generator": {"tool": "DuckPerRunPinsTest"},
				 "runIdSentinel": "424242424242424242",
				 "qaResultToken": "qa_result",
				 "sentinels": [
				  {"placeholder": "<RUNID>", "sentinel": "424242424242424242"},
				  {"placeholder": "<ASSERTIONUUID>", "sentinel": "rvfph_assertionuuid_"},
				  {"placeholder": "<PROSPECTIVE>", "sentinel": "rvfph_prospective_"}
				 ],
				 "knownTables": ["concept_s"],
				 "tableColumns": {
				  "concept_s": "id BIGINT, effectivetime VARCHAR, active VARCHAR, moduleid BIGINT, definitionstatusid BIGINT"
				 },
				 "prerequisites": [],
				 "ports": [],
				 "assertions": {
				  "%s": {"file": "%s", "sha256": "%s", "text": "%s",
				         "keywords": "component-centric-validation", "severity": "",
				         "statements": ["insert into qa_result (run_id, assertion_id, concept_id, details, component_id, table_name) select 424242424242424242, 'rvfph_assertionuuid_', id, '%s', id, 'concept_s' from rvfph_prospective_.concept_s where moduleid = %s"]}
				 },
				 "pack": {"name": "international-fixture", "version": "2026.07.27",
				          "digest": "%s", "assertions": 1}
				}
				""".formatted(uuid, file, sourceHash(file), file, file, moduleFor(file),
				digestOf(uuid, sourceHash(file)));
	}

	/**
	 * Each pack looks at a DIFFERENT module, so the two pinned runs cannot
	 * produce the same findings even by accident.
	 */
	private static String moduleFor(String file) {
		return switch (file) {
			case "pack-one.sql" -> "32506021000036107";
			case "pack-two.sql" -> "900000000000207008";
			default -> "0";
		};
	}

	private static final String GROUPS_XML = """
			<assertionGroupingStrategy>
			 <group name="everything" includeStandaloneCategories="component-centric-validation" />
			</assertionGroupingStrategy>
			""";

	@TempDir
	private Path root;

	private Path storeFile;
	private Path corpus;
	private Path work;
	private Path prospective;
	private HttpServer server;
	/** What the channel's index lists, replaced by a test to serve another index. */
	private String index;
	private final AtomicInteger fetches = new AtomicInteger();
	private ValidationReportService reportService;
	private WhitelistService whitelistService;

	@BeforeEach
	void setUp() throws Exception {
		corpus = root.resolve("corpus");
		Files.createDirectories(corpus.resolve("scripts"));
		Files.writeString(corpus.resolve("groups.xml"), GROUPS_XML);
		Files.writeString(corpus.resolve("policies.xml"), "<policyValues/>");
		for (String file : List.of("base.sql", "pack-one.sql", "pack-two.sql")) {
			Files.writeString(corpus.resolve("scripts").resolve(file), sourceOf(file));
		}

		storeFile = root.resolve("store.json");
		Files.writeString(storeFile, store(UUID_BASE, "base.sql"));

		work = root.resolve("work");
		Files.createDirectories(work);

		// Two concepts in the extension module, one in the dependency module, so
		// the two packs see 2 and 1 respectively.
		prospective = root.resolve("prospective");
		writeRf2(prospective, "Snapshot/Terminology/sct2_Concept_Snapshot_AU1000036_20260831.txt", """
				id	effectiveTime	active	moduleId	definitionStatusId
				1	20260831	1	32506021000036107	900000000000074008
				2	20260831	1	32506021000036107	900000000000074008
				3	20260831	1	900000000000207008	900000000000074008
				""");

		// Every address, so 127.0.0.2 reaches it too: a second host for the
		// channel host check.
		server = HttpServer.create(new InetSocketAddress(0), 0);
		server.createContext("/", exchange -> {
			String file = exchange.getRequestURI().getPath().substring(1);
			String text;
			if (file.equals("index.json")) {
				text = index;
			} else {
				fetches.incrementAndGet();
				text = packBody(file);
			}
			byte[] body = text.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		server.start();
		index = index(base());

		reportService = mock(ValidationReportService.class);
		whitelistService = mock(WhitelistService.class);
		when(whitelistService.isWhitelistDisabled()).thenReturn(true);
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	private static String packBody(String file) {
		return store(file.equals("pack-one.sql") ? UUID_PACK_ONE : UUID_PACK_TWO, file);
	}

	private String base() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** The channel's index: every offered version, at {@code location}. */
	private static String index(String location) {
		List<String> versions = new ArrayList<>();
		for (String[] offered : OFFERED) {
			versions.add("{\"version\": \"" + offered[1] + "\", \"uri\": \"" + location + "/" + offered[2]
					+ "\", \"sha256\": \"" + sha256(packBody(offered[2])) + "\"}");
		}
		return "{\"channel\": \"example\", \"versions\": [" + String.join(", ", versions) + "]}";
	}

	private String channel() {
		return "name=example;index=" + base() + "/index.json;packs=packOne|packTwo";
	}

	@Test
	void twoRunsOfOneReleaseWithDifferentPinsReportDifferentPacksAndFindings() {
		ValidationReport first = runWith(List.of("packOne@2026.09.1"));
		ValidationReport second = runWith(List.of("packTwo@2026.09.2"));

		// The reports name what produced them, base included.
		assertEquals(List.of("international-fixture", "packOne"), packNames(first));
		assertEquals(List.of("international-fixture", "packTwo"), packNames(second));

		// And the findings follow the pins rather than the pins being accepted
		// and ignored: each pack counts a different module.
		assertEquals(2L, failureCount(first, UUID_PACK_ONE),
				"packOne's assertion ran and saw the two extension-module concepts");
		assertEquals(1L, failureCount(second, UUID_PACK_TWO),
				"packTwo's assertion ran and saw the one dependency-module concept");
		assertThrows(AssertionError.class, () -> failureCount(first, UUID_PACK_TWO),
				"the first run must not have executed the other pin's assertion");
		assertThrows(AssertionError.class, () -> failureCount(second, UUID_PACK_ONE),
				"nor the second the first's");
	}

	@Test
	void thePinnedCorpusIsCachedByPinSetAndNotRebuiltPerRun() throws Exception {
		DuckAssertionService service = service();
		List<String> pins = List.of("packOne@2026.09.1");

		DuckAssertionService.Corpus once = service.corpusFor(pins);
		DuckAssertionService.Corpus twice = service.corpusFor(pins);
		assertSame(once, twice, "the same pins must not re-fetch and re-merge");
		assertEquals(1, fetches.get(), "one HTTP fetch for two resolutions");

		DuckAssertionService.Corpus other =
				service.corpusFor(List.of("packTwo@2026.09.2"));
		assertNotSame(once, other);
		assertEquals(2, fetches.get());
	}

	@Test
	void aRunThatPinsNothingGetsWhatTheDeploymentIsServing() throws Exception {
		DuckAssertionService service = service();
		assertSame(service.currentCorpus().store(), service.corpusFor(List.of()).store());
		assertSame(service.currentCorpus().store(), service.corpusFor(null).store());
		assertEquals(0, fetches.get(), "and nothing is fetched for it");
	}

	@Test
	void aChannelVersionWhoseBytesAreNotItsDigestIsRefusedRatherThanRunWithFewerAssertions() throws Exception {
		index = index(base()).replace(sha256(packBody("pack-one.sql")), "0".repeat(64));
		DuckAssertionService service = service();
		IOException e = assertThrows(IOException.class,
				() -> service.corpusFor(List.of("packOne@2026.09.1")));
		assertTrue(e.getMessage().toLowerCase().contains("sha256")
						|| e.getMessage().toLowerCase().contains("digest"),
				e.getMessage());
		// And the deployment's corpus is untouched: a pinned run is not a
		// deployment change, and a failed one must not leave the engine altered.
		assertEquals(1, service.currentCorpus().store().assertions().size());
	}

	@Test
	void aRunMayNotPinALocationADigestOrACredential() throws Exception {
		DuckAssertionService service = service();
		String body = packBody("pack-two.sql");
		Path packFile = Files.writeString(root.resolve("pack-two.json"), body);
		List<String> refused = List.of(
				"name=packTwo;version=1;uri=" + packFile.toUri() + ";sha256=" + sha256(body),
				"name=packOne;version=1;uri=" + base() + "/pack-one.sql;sha256=" + sha256(packBody("pack-one.sql")),
				"packOne@2026.09.1;authHeader=Bearer secret",
				packFile.toUri().toString(),
				"uri=" + base() + "/pack-one.sql");
		for (String pin : refused) {
			IOException e = assertThrows(IOException.class, () -> service.corpusFor(List.of(pin)), pin);
			assertTrue(e.getMessage().contains("accepts only name@version"), e.getMessage());
			assertFalse(e.getMessage().contains("secret"), "a credential must not be echoed: " + e.getMessage());
		}
		assertEquals(0, fetches.get(), "nothing a refused pin names is fetched");

		// The check the endpoints apply before a run is queued, which they answer with 400.
		assertEquals(refused.size(), AssertionPackChannels.invalidRunPins(refused).size());
		assertEquals(List.of(), AssertionPackChannels.invalidRunPins(List.of("packOne@2026.09.1", " ")));
		assertTrue(AssertionPackChannels.runPinMessage(AssertionPackChannels.invalidRunPins(refused))
				.contains("authHeader=<redacted>"));
	}

	@Test
	void aVersionNoChannelOffersIsRefused() {
		DuckAssertionService service = service();
		IOException unknownVersion = assertThrows(IOException.class,
				() -> service.corpusFor(List.of("packOne@2099.01.01")));
		assertTrue(unknownVersion.getMessage().contains("offers no version"), unknownVersion.getMessage());
		IOException unknownPack = assertThrows(IOException.class,
				() -> service.corpusFor(List.of("someoneElsesPack@1")));
		assertTrue(unknownPack.getMessage().contains("no configured channel serves"), unknownPack.getMessage());
		assertEquals(0, fetches.get());
	}

	@Test
	void anIndexPointingAtAnotherHostIsRefused() {
		// 127.0.0.2 reaches the same server, so only the host check can refuse it.
		index = index("http://127.0.0.2:" + server.getAddress().getPort());
		IOException e = assertThrows(IOException.class, () -> service().corpusFor(List.of("packOne@2026.09.1")));
		assertTrue(e.getMessage().contains("not at its index's scheme and host"), e.getMessage());
		assertEquals(0, fetches.get());

		// Unless the channel configuration lists that host.
		DuckAssertionService allowing = new DuckAssertionService(
				new DuckStoreLocator(storeFile.toString(), corpus.toString()),
				corpus.toString(), List.of(), List.of(channel() + ";allowedHosts=127.0.0.2"));
		assertEquals(2, assertDoesNotThrow(() -> allowing.corpusFor(List.of("packOne@2026.09.1")))
				.store().assertions().size(), "the base's assertion and the pinned pack's");
	}

	@Test
	void aCredentialNeverReachesALogLine() {
		AssertionPackChannels.Channel channel = AssertionPackChannels.parse(
				List.of(channel() + ";authHeader=Bearer secret")).get(0);
		assertFalse(channel.toString().contains("secret"), channel.toString());
		AssertionPackFetcher.Source source = new AssertionPackFetcher.Source("p", "1",
				java.net.URI.create(base()), "0".repeat(64), "Bearer secret");
		assertFalse(source.toString().contains("secret"), source.toString());
	}

	private List<String> packNames(ValidationReport report) {
		List<String> names = new ArrayList<>();
		for (ValidationReport.AssertionPackRecord pack : report.getAssertionPacks()) {
			names.add(pack.name());
		}
		return names;
	}

	private static long failureCount(ValidationReport report, String uuid) {
		UUID wanted = UUID.fromString(uuid);
		for (List<TestRunItem> bucket : List.of(report.getAssertionsFailed(),
				report.getAssertionsPassed(), report.getAssertionsWarning(),
				report.getAssertionsSkipped())) {
			for (TestRunItem candidate : bucket) {
				if (wanted.equals(candidate.getAssertionUuid())) {
					return candidate.getFailureCount();
				}
			}
		}
		throw new AssertionError(uuid + " was not reported at all");
	}

	private ValidationReport runWith(List<String> pins) {
		MysqlExecutionConfig config = new MysqlExecutionConfig(11L);
		config.setGroupNames(List.of("everything"));
		config.setFailureExportMax(10);
		config.setAssertionPacks(pins);

		ValidationRunConfig runConfig = new ValidationRunConfig();
		runConfig.setRunId(config.getExecutionId());
		ValidationStatusReport status = new ValidationStatusReport(runConfig);
		validationService().runValidations(config,
				new DuckDbValidationService.ReleaseDirectories(prospective, null, (Path) null),
				"storage/", status);
		assertEquals(List.of(), status.getFailureMessages());
		return status.getResultReport();
	}

	private DuckDbValidationService validationService() {
		DuckAssertionService assertions = service();
		return new DuckDbValidationService(reportService, archiveCollector(), whitelistService,
				new ReleaseAcquisitionService(),
				new DuckStoreLocator(storeFile.toString(), corpus.toString()),
				corpus.toString(), work.toString(), "empty-rf2-snapshot.zip", "qa_result", 0, "", false, "", 0,
				provider(assertions));
	}

	private DuckAssertionService service() {
		return new DuckAssertionService(
				new DuckStoreLocator(storeFile.toString(), corpus.toString()),
				corpus.toString(), List.of(), List.of(channel()));
	}

	private static ObjectProvider<DuckAssertionService> provider(DuckAssertionService service) {
		return new ObjectProvider<>() {
			@Override
			public DuckAssertionService getObject() {
				return service;
			}

			@Override
			public DuckAssertionService getObject(Object... args) {
				return service;
			}

			@Override
			public DuckAssertionService getIfAvailable() {
				return service;
			}

			@Override
			public DuckAssertionService getIfUnique() {
				return service;
			}
		};
	}

	private static FailureArchiveCollector archiveCollector() {
		FailureArchiveCollector collector = new FailureArchiveCollector();
		collector.setArchiveFailures(true);
		collector.setReportService(new ValidationReportService());
		collector.setWorkDirectory(System.getProperty("java.io.tmpdir"));
		return collector;
	}

	private static String sourceOf(String file) {
		return "-- " + file + "\ninsert into qa_result select 1;\n";
	}

	/** The publisher's digest for a one-assertion, no-prerequisite store. */
	private static String digestOf(String uuid, String sourceHash) {
		return "sha256:" + sha256Hex(uuid + "\t" + sourceHash + "\n--\n");
	}

	private static String sourceHash(String file) {
		return sha256Hex(sourceOf(file)).substring(0, 16);
	}

	private static String sha256(String body) {
		return sha256Hex(body);
	}

	private static String sha256Hex(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static void writeRf2(Path releaseRoot, String relativePath, String content)
			throws IOException {
		Path file = releaseRoot.resolve(relativePath);
		Files.createDirectories(file.getParent());
		Files.writeString(file, content);
	}
}
