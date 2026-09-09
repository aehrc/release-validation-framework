package org.ihtsdo.rvf.core.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What archiving every test type costs, against the code that does it.
 *
 * <p>{@code bench/ArchiveBench.java} chose the design on these numbers; this
 * keeps the answer honest as the code changes, and is the reason two things in
 * the implementation are the way they are.
 *
 * <p>Measured on an Apple M-series laptop at 1,000,000 SQL rows and 200,000
 * Drools and MRCM rows - a bad night, and the AU nightly's largest single Drools
 * rule reports 144,212 instances on its own:
 *
 * <pre>
 *   COPY qa_result to parquet, which was the whole job    ~45 ms
 *   Appending 200,000 Drools and MRCM rows               ~260 ms
 *   Unioning the fragments into one parquet               ~70 ms
 * </pre>
 *
 * <p>Roughly 300ms added to a run measured in hundreds of seconds - the AU
 * nightly is currently 464s, and MRCM alone was 816s before it was optimised.
 *
 * <p>The assertions here are deliberately loose. A timing test that fails on a
 * loaded CI agent teaches everyone to ignore it, so these only catch a change of
 * ORDER: the ceilings are roughly 20x the measured values, which a regression
 * like the one below would blow through and ordinary variance would not.
 */
class FailureArchivePerformanceTest {

	private static final int SQL_ROWS = 1_000_000;
	private static final int OTHER_ROWS = 200_000;

	private Path work;
	private FailureArchiveCollector collector;

	@BeforeEach
	void setUp() throws IOException {
		work = Files.createTempDirectory("archive-perf-");
		collector = new FailureArchiveCollector(new ValidationReportService() {
			@Override
			public void writeFailureArchive(String reportStorage, File file) {
				// The upload is the job store's cost, not this change's.
			}
		}, work.toString(), true);
	}

	@AfterEach
	void tearDown() throws IOException {
		try (var paths = Files.walk(work)) {
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	private static List<FailureArchiveRow> rows(String assertionId, int count) {
		List<FailureArchiveRow> rows = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			rows.add(new FailureArchiveRow(1788693845351L, assertionId, 100000000L + i,
					"An active term should not contain tabs, newlines, or characters @, $, #, \\.",
					"" + (300000000L + i)));
		}
		return rows;
	}

	/** The SQL task's own COPY, which is what the archive used to cost in full. */
	private Path sqlFragment() throws Exception {
		Path out = work.resolve("sql.parquet");
		try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
				Statement st = c.createStatement()) {
			st.execute(FailureArchiveCollector.FRAGMENT_DDL);
			st.execute("INSERT INTO rows_ SELECT i, 1788693845351, 'a' || (i % 500), 100000000 + i, "
					+ "'concept ' || i || ' violates the assertion', '' || (200000000 + i), "
					+ "'sct2_Concept', false FROM range(" + SQL_ROWS + ") t(i)");
			st.execute("COPY (SELECT * FROM rows_) TO '" + out + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
		}
		return out;
	}

	/**
	 * The added cost, start to finish, against the old baseline.
	 *
	 * <p>Printed rather than only asserted, because the number is the point and
	 * the next person to change this should be able to read it off a test run.
	 */
	@Test
	void archivingEveryTestTypeCostsAboutAThirdOfASecond() throws Exception {
		long t0 = System.nanoTime();
		Path sql = sqlFragment();
		long baselineMs = msSince(t0);

		t0 = System.nanoTime();
		Path drools = collector.writeFragment("drools", rows("drools-rule", OTHER_ROWS));
		long stageMs = msSince(t0);

		collector.register("perf", sql);
		collector.register("perf", drools);

		t0 = System.nanoTime();
		collector.assemble("perf");
		long assembleMs = msSince(t0);

		// The fixture number includes GENERATING the million rows, so it is not
		// the old baseline - bench/ArchiveBench.java measures the COPY alone at
		// ~45ms. It is printed for scale, not for comparison.
		System.out.printf("%n  fixture: build + COPY 1M SQL rows   %5d ms%n"
				+ "  staging %,d Drools/MRCM rows      %5d ms%n"
				+ "  assembling one archive              %5d ms%n"
				+ "  ADDED                               %5d ms%n%n",
				baselineMs, OTHER_ROWS, stageMs, assembleMs, stageMs + assembleMs);

		// ~260ms measured. A JDBC batch instead of the appender took 22,124ms for
		// the same rows, so this ceiling is what stops that being reintroduced
		// without anyone noticing.
		assertTrue(stageMs < 5_000,
				"staging " + OTHER_ROWS + " rows took " + stageMs + "ms; the appender should be well under a second");
		// ~70ms measured, and the union is a full re-read of the SQL rows, so
		// this is the number that would move if the archive stopped being one
		// file or the compression changed.
		assertTrue(assembleMs < 5_000,
				"assembling the archive took " + assembleMs + "ms");
	}

	private static long msSince(long nanos) {
		return (System.nanoTime() - nanos) / 1_000_000;
	}
}
