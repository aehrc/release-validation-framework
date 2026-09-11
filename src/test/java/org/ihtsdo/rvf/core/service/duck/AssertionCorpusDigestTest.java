package org.ihtsdo.rvf.core.service.duck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ihtsdo.rvf.core.data.model.Assertion;
import org.ihtsdo.rvf.core.data.model.TestRunItem;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What every assertion in the corpus finds, recorded one assertion at a time.
 *
 * <p>This is the half of engine parity that can run anywhere. The other half is
 * differential - the same release through both engines, divergences classified
 * against {@code ci/known-engine-divergences.json} - and it needs a MySQL, so it
 * needs Docker, so it cannot be the check that guards a change on a laptop or on
 * an agent that has none. What it CAN be guarded by is this: the complete
 * behaviour of all {@value #EXPECTED_ASSERTIONS}-odd assertions against a real
 * release, digested per assertion and committed, so any change to the engine,
 * the binder, the materialiser or a transpilation has to say which assertions it
 * moved and why.
 *
 * <p>Per-assertion is the whole point. A total is not a parity check: two
 * offsetting differences leave it unchanged, and the corpus has already produced
 * exactly that - a store republish that lost 12 setup statements did not move
 * the findings total at all, because the macros it lost were another edition's.
 *
 * <h2>Why a digest and not just a count</h2>
 *
 * A count answers "how many rows", which a transpilation can preserve while
 * changing which components it names - the case that matters most for the 43
 * {@code REGEXP} rewrites, where a regex that matches the wrong thing still
 * matches something. The digest is over the sorted {@code concept_id},
 * {@code component_id}, {@code table_name} and {@code details} of every row the
 * assertion inserted, so the recorded value distinguishes "same answer" from
 * "same size answer".
 *
 * <h2>Why this fixture</h2>
 *
 * {@code SnomedCT_RegressionTest_20130731} over {@code _20130131} is RVF's own
 * pair, already committed, 58 and 57 RF2 files - so this needs no clone, no
 * bucket, no network, and it supplies a PREVIOUS release, which 81 of the
 * international assertions read and would otherwise skip.
 *
 * <p>No DEPENDENCY release is supplied, because there is no committed extension
 * to be one. The assertions that need it therefore do not run, which is a fact
 * this test records rather than hides: see {@link #everyAssertionRanOrSaidWhy}.
 *
 * <h2>Two rules this class exists to enforce</h2>
 *
 * <ul>
 * <li><b>An assertion that did not run must not pass.</b> A parity check over a
 *     skipped assertion compares two empty results and is green. That is not a
 *     hypothetical: {@code RangeSetProbe} reported 134 expressions identical
 *     while BOTH arms ran the old code, and the AMT category sat in a report as
 *     0 findings while nothing had executed it. So status is part of the
 *     recorded value, and an ERROR fails the build outright.
 * <li><b>An empty corpus must not pass.</b> If a change makes every assertion
 *     find nothing, every count still matches its own recording only if the
 *     recording was made after the change - so the number of assertions
 *     producing findings is asserted explicitly, against a floor as well as
 *     against the golden file.
 * </ul>
 *
 * <h2>Updating the golden file</h2>
 *
 * <pre>
 *   mvn test -Dtest=AssertionCorpusDigestTest -Dduck.digests.write=true
 * </pre>
 *
 * and commit the diff WITH the reason each moved assertion moved. A diff with no
 * explanation is the thing this test is meant to prevent.
 */
class AssertionCorpusDigestTest {

	/** Sanity floor on the corpus size, so a truncated store fails loudly. */
	private static final int EXPECTED_ASSERTIONS = 300;

	private static final Path STORE =
			Path.of("src/main/resources/duck/store.json");
	private static final Path PROSPECTIVE =
			Path.of("src/test/resources/SnomedCT_RegressionTest_20130731");
	private static final Path PREVIOUS =
			Path.of("src/test/resources/SnomedCT_RegressionTest_20130131");
	private static final Path GOLDEN =
			Path.of("src/test/resources/duck/assertion-digests.tsv");

	private static final Path KNOWN_ERRORS =
			Path.of("duck/known-assertion-errors.json");
	private static final long RUN_ID = 1L;
	private static final String QA_RESULT = "rvf_results.qa_result";

	/** Empty findings digest, so "ran and found nothing" is a recorded value. */
	private static final String EMPTY = sha256("");

	/**
	 * One assertion's outcome. {@code status} is the coarse fact - did it run -
	 * and {@code digest} is what it found; both are recorded, because either
	 * alone can be preserved by a change that breaks the other.
	 */
	private record Outcome(String file, String status, long findings, String digest) {

		String line(String uuid) {
			return String.join("\t", uuid, file, status, String.valueOf(findings), digest);
		}
	}

	private static final Map<String, Outcome> ACTUAL = new TreeMap<>();
	private static Map<String, Outcome> golden;

	@BeforeAll
	static void runTheWholeCorpus() throws Exception {
		DuckStore store = DuckStore.read(STORE);
		String json = Files.readString(STORE);
		Map<String, String> tableColumns = tableColumns(json);

		Class.forName("org.duckdb.DuckDBDriver");
		try (Connection con = DriverManager.getConnection("jdbc:duckdb:")) {
			DuckMaterialiser.materialise(con, PROSPECTIVE, "prospective", tableColumns);
			DuckMaterialiser.materialise(con, PREVIOUS, "previous", tableColumns);
			session(con);
			createResultTable(con);

			// The release version is read from the directory name exactly as the
			// probe does, because <VERSION> is bound into assertions that compare
			// effectiveTime against the release being validated. Hardcoding it
			// here would make the fixture and the binding disagree the first time
			// the fixture changed.
			// An empty dependency schema, which is what a release with no
			// dependency actually has - and `releaseAsAnEdition=true` is what
			// the nightly submits. Materialising an empty directory gives every
			// declared table a zero-row placeholder, so a statement whose only
			// use of the dependency is an anti-join gets the same answer it
			// would get from a real empty release instead of being skipped.
			Path noDependency = Files.createTempDirectory("rvf-empty-dependency");
			DuckMaterialiser.materialise(con, noDependency, "empty", tableColumns);

			DuckBinder binder = new DuckBinder(store.sentinels(), new DuckBinder.Config(
					RUN_ID, "prospective", "previous", null, QA_RESULT,
					null, List.of(), "20130731", "empty"));

			DuckDbAssertionExecutionService service =
					new DuckDbAssertionExecutionService(store, binder, con);
			// Through the production service, and let SetupFailedException out:
			// a setup failure means nothing below is trustworthy, and the one
			// time that was reported as a count instead of a throw it hid a
			// 12-statement regression behind an unmoved findings total.
			service.prepareSchema();

			List<TestRunItem> items = service.execute(corpusInExecutionOrder(store));
			Map<String, String> digests = digestsByAssertion(con);
			Map<String, Long> counts = service.failureCounts(RUN_ID, QA_RESULT);


			// The status alone is not actionable: an ERROR needs its message to
			// be triaged, and the message is not in the golden file because it
			// is a diagnostic, not a contract.
			items.stream()
					.filter(i -> "ERROR".equals(status(i)))
					.forEach(i -> System.out.println("  ERROR "
							+ store.assertions().get(String.valueOf(i.getAssertionUuid())).file()
							+ ": " + i.getFailureMessage()));
			for (TestRunItem item : items) {
				String uuid = String.valueOf(item.getAssertionUuid());
				ACTUAL.put(uuid, new Outcome(
						store.assertions().get(uuid).file(),
						status(item),
						counts.getOrDefault(uuid, 0L),
						digests.getOrDefault(uuid, EMPTY)));
			}
		}

		if (Boolean.getBoolean("duck.digests.write")) {
			StringBuilder sb = new StringBuilder("""
					# What each assertion finds against SnomedCT_RegressionTest_20130731
					# over _20130131, recorded by AssertionCorpusDigestTest.
					#
					# uuid	file	status	findings	sha256(sorted rows)
					#
					# status RAN     - executed; digest is what it found
					#        NOT_RUN - needed a release this fixture does not supply
					#        ERROR   - failed to execute, and fails the build
					#
					# Regenerate with -Dduck.digests.write=true, and commit the diff
					# WITH the reason each moved assertion moved.
					""");
			ACTUAL.forEach((uuid, o) -> sb.append(o.line(uuid)).append('\n'));
			Files.createDirectories(GOLDEN.getParent());
			Files.writeString(GOLDEN, sb.toString());
		}
		golden = readGolden();
	}

	/**
	 * The corpus, with the {@code resource} assertions first.
	 *
	 * <p>Not cosmetic ordering. Those assertions build the shared intermediate
	 * tables other assertions select from - {@code res_edited_active_concepts},
	 * {@code tmp_pt}, {@code ancestors} - so in store order 12 assertions fail on
	 * a missing table that nothing was left to create. Production reaches the
	 * same order through group resolution; this reaches it directly, because the
	 * point here is to run EVERY assertion rather than a group selection.
	 */
	private static List<Assertion> corpusInExecutionOrder(DuckStore store) {
		List<Assertion> resource = new ArrayList<>();
		List<Assertion> rest = new ArrayList<>();
		store.assertions().forEach((uuid, stored) -> {
			Assertion a = new Assertion();
			a.setUuid(UUID.fromString(uuid));
			a.setAssertionText(stored.text());
			a.setKeywords(stored.keywords());
			a.setSeverity(stored.severity());
			boolean isResource = java.util.Arrays.stream(stored.keywords().split(","))
					.map(String::trim).anyMatch("resource"::equals);
			(isResource ? resource : rest).add(a);
		});
		resource.addAll(rest);
		return resource;
	}

	private static String status(TestRunItem item) {
		String message = item.getFailureMessage();
		if (message == null || message.isBlank()) {
			return "RAN";
		}
		// The service distinguishes these two in its message, and the difference
		// is the whole point: one is a fact about the fixture, the other is a
		// broken assertion.
		return message.startsWith("Not run: requires") ? "NOT_RUN" : "ERROR";
	}

	/**
	 * One digest per assertion, from one ordered pass over qa_result.
	 *
	 * <p>Ordered in SQL and folded in Java rather than a query per assertion:
	 * the corpus is 360 assertions and the N+1 shape is measurable next to the
	 * run. The row text is built in SQL so the ordering is over the same string
	 * that is hashed - sorting on the columns and hashing a different rendering
	 * would make the digest depend on DuckDB's collation.
	 */
	private static Map<String, String> digestsByAssertion(Connection con) throws Exception {
		Map<String, String> out = new LinkedHashMap<>();
		String rowText = "coalesce(concept_id::VARCHAR, '') || '|' || coalesce(component_id, '') "
				+ "|| '|' || coalesce(table_name, '') || '|' || coalesce(details, '')";
		String sql = "SELECT assertion_id, " + rowText + " AS row_text FROM " + QA_RESULT
				+ " WHERE run_id = " + RUN_ID + " ORDER BY assertion_id, row_text";
		String current = null;
		StringBuilder acc = new StringBuilder();
		try (Statement st = con.createStatement(); ResultSet rs = st.executeQuery(sql)) {
			while (rs.next()) {
				String id = rs.getString(1);
				if (!id.equals(current)) {
					if (current != null) {
						out.put(current, sha256(acc.toString()));
					}
					current = id;
					acc.setLength(0);
				}
				acc.append(rs.getString(2)).append('\n');
			}
		}
		if (current != null) {
			out.put(current, sha256(acc.toString()));
		}
		return out;
	}

	private static Map<String, Outcome> readGolden() throws Exception {
		Map<String, Outcome> out = new TreeMap<>();
		if (!Files.exists(GOLDEN)) {
			return out;
		}
		for (String line : Files.readAllLines(GOLDEN)) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			String[] f = line.split("\t", -1);
			out.put(f[0], new Outcome(f[1], f[2], Long.parseLong(f[3]), f[4]));
		}
		return out;
	}

	// ---- the checks -----------------------------------------------------

	@Test
	void everyAssertionInTheStoreHasARecordedOutcome() {
		assertTrue(ACTUAL.size() >= EXPECTED_ASSERTIONS,
				"only " + ACTUAL.size() + " assertions ran - the store looks truncated");
		Set<String> unrecorded = new TreeSet<>(ACTUAL.keySet());
		unrecorded.removeAll(golden.keySet());
		Set<String> stale = new TreeSet<>(golden.keySet());
		stale.removeAll(ACTUAL.keySet());
		assertTrue(unrecorded.isEmpty() && stale.isEmpty(),
				() -> "the recording and the store disagree about which assertions exist.\n"
						+ describe("in the store, never recorded", unrecorded)
						+ describe("recorded, no longer in the store", stale)
						+ "Regenerate with -Dduck.digests.write=true once you know why.");
	}

	/**
	 * An assertion must run, or say which release it needed, or be a known defect.
	 *
	 * <p>Bidirectional, for the same reason the store gate is: an unlisted error
	 * fails the build, and a listed error that no longer errors fails it too. A
	 * one-directional check would let a fix land while the file still claimed
	 * the assertion was broken, and the next person would read the claim rather
	 * than the code.
	 *
	 * <p>NOT_RUN is accepted only where the recording already says so, because
	 * an assertion that quietly stopped running is exactly the regression that
	 * looks like a pass.
	 */
	@Test
	void everyAssertionRanOrSaidWhy() throws Exception {
		Map<String, String> known = knownErrors();
		Set<String> unexplained = new TreeSet<>();
		Set<String> fixed = new TreeSet<>(known.keySet());
		Set<String> newlyNotRun = new TreeSet<>();
		ACTUAL.forEach((uuid, o) -> {
			if ("ERROR".equals(o.status())) {
				if (known.containsKey(uuid)) {
					fixed.remove(uuid);
				} else {
					unexplained.add(o.file() + " (" + uuid + ")");
				}
			} else if ("NOT_RUN".equals(o.status())
					&& !"NOT_RUN".equals(golden.getOrDefault(uuid, o).status())) {
				newlyNotRun.add(o.file() + " (" + uuid + ")");
			}
		});
		assertTrue(unexplained.isEmpty() && fixed.isEmpty() && newlyNotRun.isEmpty(),
				() -> "assertions that cannot be trusted to have been checked.\n"
						+ describe("failed to execute, and not named in "
								+ KNOWN_ERRORS.getFileName(), unexplained)
						+ describe("named in " + KNOWN_ERRORS.getFileName()
								+ " but executing now - remove the entry", fixed)
						+ describe("stopped running, and used to run", newlyNotRun));
	}

	/** uuid to the reason it cannot execute, from the committed file. */
	private static Map<String, String> knownErrors() throws Exception {
		Map<String, String> out = new LinkedHashMap<>();
		JsonNode errors = new ObjectMapper().readTree(Files.readString(KNOWN_ERRORS))
				.path("errors");
		errors.fieldNames().forEachRemaining(
				uuid -> out.put(uuid, errors.path(uuid).path("cause").asText()));
		return out;
	}

	@Test
	void whatEachAssertionFindsIsUnchanged() {
		List<String> moved = new ArrayList<>();
		ACTUAL.forEach((uuid, now) -> {
			Outcome then = golden.get(uuid);
			if (then == null || now.equals(then)) {
				return;
			}
			moved.add("  " + now.file() + " (" + uuid + ")\n"
					+ "    was " + then.status() + " " + then.findings() + " findings "
					+ then.digest().substring(0, 12) + "\n"
					+ "    now " + now.status() + " " + now.findings() + " findings "
					+ now.digest().substring(0, 12));
		});
		assertTrue(moved.isEmpty(),
				() -> moved.size() + " assertion(s) changed what they find:\n"
						+ String.join("\n", moved.subList(0, Math.min(20, moved.size())))
						+ (moved.size() > 20 ? "\n  ... and " + (moved.size() - 20) + " more" : "")
						+ "\nIf each is intended, regenerate with -Dduck.digests.write=true"
						+ " and commit the diff with the reason.");
	}

	/**
	 * The corpus must not be silently empty.
	 *
	 * <p>Every other check here compares against a recording, so a change that
	 * made every assertion find nothing would be caught only if the recording
	 * predates it. This one does not compare: it asserts that assertions are
	 * still finding things at all, which is the property that makes the digests
	 * worth anything.
	 */
	@Test
	void theFixtureStillProducesFindings() {
		long producing = ACTUAL.values().stream().filter(o -> o.findings() > 0).count();
		long expected = golden.values().stream().filter(o -> o.findings() > 0).count();
		assertTrue(producing > 0,
				"no assertion found anything - two empty results agreeing proves nothing");
		assertEquals(expected, producing,
				"the number of assertions producing findings moved, so the digests below"
						+ " are comparing a different corpus");
	}

	/**
	 * Coverage is a ratchet: it may rise, and a fall has to be argued for here.
	 *
	 * <p>{@link #theFixtureStillProducesFindings()} compares against the golden
	 * file, which is regenerated with {@code -Dduck.digests.write=true} - so a
	 * change that dropped twenty assertions to silent passes the moment someone
	 * regenerates, and the diff that would have shown it is 360 lines of hashes.
	 * This floor is a separate number in the source, and lowering it is an edit
	 * a reviewer can see.
	 *
	 * <p>The figure is where deliberate authoring got to on 2026-09-11: 338 of
	 * the 360 find something, and 16 of the remainder are {@code -proc.sql} or
	 * {@code res-table-*} assertions that build tables rather than report
	 * findings, so 338 of 343 that can. See duck/PLAN.md 3.19.
	 */
	private static final int COVERAGE_FLOOR = 338;

	@Test
	void coverageDoesNotRegress() {
		long producing = ACTUAL.values().stream().filter(o -> o.findings() > 0).count();
		assertTrue(producing >= COVERAGE_FLOOR,
				() -> "assertion coverage fell to " + producing + " from a floor of "
						+ COVERAGE_FLOOR + ". Either the fixture lost content the"
						+ " assertions were authored against - check ci/author_*_fixture.py"
						+ " ran, and in order - or the drop is intended and this floor"
						+ " moves in the same commit, with the reason.");
	}

	// ---- fixture plumbing -----------------------------------------------

	/**
	 * The two session settings the DuckDB path cannot run without.
	 *
	 * <p>Neither carries to another connection, so anything opening its own must
	 * repeat them - which is why they are here and not assumed.
	 */
	private static void session(Connection con) throws Exception {
		try (Statement st = con.createStatement()) {
			// pre-requisites.sql names its inputs unqualified - "FROM concept_s" -
			// so without a default schema every one of them fails.
			st.execute("SET search_path='prospective'");
			// MySQL casts freely, DuckDB does not: length() over a BIGINT
			// referencedcomponentid is the difference between one macro working
			// and one assertion failing for a reason that reads like content.
			st.execute("SET old_implicit_casting=true");
		}
	}

	private static void createResultTable(Connection con) throws Exception {
		try (Statement st = con.createStatement()) {
			st.execute("CREATE SCHEMA IF NOT EXISTS rvf_results");
			// Mirrors RVF's own qa_result, including skip_module_check, so an
			// assertion naming that column can insert into it.
			st.execute("CREATE TABLE " + QA_RESULT + " ("
					+ "id BIGINT, run_id BIGINT, assertion_id VARCHAR, concept_id BIGINT, "
					+ "details VARCHAR, component_id VARCHAR, table_name VARCHAR, "
					+ "skip_module_check BOOLEAN)");
		}
	}

	private static Map<String, String> tableColumns(String json) throws Exception {
		Map<String, String> out = new LinkedHashMap<>();
		JsonNode node = new ObjectMapper().readTree(json).path("tableColumns");
		node.fieldNames().forEachRemaining(t -> out.put(t, node.path(t).asText()));
		return out;
	}

	private static String describe(String label, Set<String> names) {
		if (names.isEmpty()) {
			return "";
		}
		List<String> shown = new ArrayList<>(names).subList(0, Math.min(15, names.size()));
		return "  " + names.size() + " " + label + ":\n    "
				+ String.join("\n    ", shown)
				+ (names.size() > shown.size() ? "\n    ..." : "") + "\n";
	}

	private static String sha256(String s) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(s.getBytes(StandardCharsets.UTF_8)));
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}
}
