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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The archive has to end up holding every test type's rows, and it has to do it
 * while three validators are writing into it at once.
 *
 * <p>The defect being fixed was not subtle once seen: {@code failures.parquet}
 * was written from {@code qa_result}, only SQL assertion execution writes there,
 * and so the uncapped export returned a bare header for every Drools and MRCM
 * assertion. What made it survive is that nothing tested the archive against
 * more than one producer.
 */
class FailureArchiveCollectorTest {

	private Path work;
	private Path uploaded;
	private FailureArchiveCollector collector;

	@BeforeEach
	void setUp() throws IOException {
		work = Files.createTempDirectory("collector-test-");
		collector = new FailureArchiveCollector();
		collector.setWorkDirectory(work.toString());
		collector.setArchiveFailures(true);
		collector.setReportService(new ValidationReportService() {
			@Override
			public void writeFailureArchive(String reportStorage, File file) throws IOException {
				uploaded = work.resolve("uploaded-" + reportStorage + ".parquet");
				Files.copy(file.toPath(), uploaded);
			}
		});
	}

	@AfterEach
	void tearDown() throws IOException {
		try (var paths = Files.walk(work)) {
			paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
		}
	}

	private static List<FailureArchiveRow> rows(String assertionId, int count) {
		List<FailureArchiveRow> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			rows.add(new FailureArchiveRow(1788693845351L, assertionId,
					100000000L + i, "concept " + i + " violates it", "" + (200000000L + i)));
		}
		return rows;
	}

	private long countIn(Path parquet, String assertionId) throws Exception {
		try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
				PreparedStatement ps = c.prepareStatement(
						"SELECT COUNT(*) FROM read_parquet(?) WHERE assertion_id = ?")) {
			ps.setString(1, parquet.toString());
			ps.setString(2, assertionId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getLong(1) : -1;
			}
		}
	}

	/** A stand-in for the SQL task's own COPY out of qa_result. */
	private Path sqlFragment(int count) throws Exception {
		Path out = work.resolve("sql-" + System.nanoTime() + ".parquet");
		try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
				Statement st = c.createStatement()) {
			st.execute(FailureArchiveCollector.FRAGMENT_DDL);
			FailureArchiveRow.appendAll(c, "rows_", rows("sql-assertion", count));
			st.execute("COPY (SELECT * FROM rows_) TO '" + out + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
		}
		return out;
	}

	// The whole point. Before this, the second and third of these were absent
	// from the archive and their export returned a header and nothing else.
	@Test
	void archivesEveryTestTypeAndNotOnlyTheSqlOne() throws Exception {
		collector.register("run-1", sqlFragment(40));
		collector.register("run-1", collector.writeFragment("drools", rows("drools-rule", 5158)));
		collector.register("run-1", collector.writeFragment("mrcm", rows("mrcm-check", 2)));

		collector.assemble("run-1");

		assertNotNull(uploaded, "the archive should have been uploaded");
		assertEquals(40, countIn(uploaded, "sql-assertion"));
		assertEquals(5158, countIn(uploaded, "drools-rule"));
		assertEquals(2, countIn(uploaded, "mrcm-check"));
	}

	// failureExportMax caps the report's sample at 10 or 100. The archive must
	// not be capped at anything, or it answers the same question the report
	// already answers.
	@Test
	void archivesEveryRowRatherThanTheExportedSample() throws Exception {
		collector.register("run-2", collector.writeFragment("drools", rows("drools-rule", 5158)));
		collector.assemble("run-2");
		assertEquals(5158, countIn(uploaded, "drools-rule"));
	}

	/*
	 * The three validators run in parallel - ValidationRunner submits each to an
	 * executor - which is exactly why the archive could not simply be written by
	 * one of them. Registration has to survive that.
	 */
	@Test
	void collectsFromValidatorsRunningAtTheSameTime() throws Exception {
		int validators = 3;
		ExecutorService pool = Executors.newFixedThreadPool(validators);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(validators);
		for (int i = 0; i < validators; i++) {
			String id = "validator-" + i;
			pool.submit(() -> {
				try {
					start.await();
					collector.register("run-3", collector.writeFragment(id, rows(id, 100)));
				} catch (Exception e) {
					throw new RuntimeException(e);
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		assertTrue(done.await(60, TimeUnit.SECONDS), "the validators should have finished");
		pool.shutdown();

		assertEquals(3, collector.stagedCount("run-3"));
		collector.assemble("run-3");
		for (int i = 0; i < validators; i++) {
			assertEquals(100, countIn(uploaded, "validator-" + i));
		}
	}

	// The switch has to keep meaning what it says, or turning archiving off
	// stops being a way to turn archiving off.
	@Test
	void writesNothingWhenArchivingIsDisabled() {
		collector.setArchiveFailures(false);
		assertNull(collector.writeFragment("drools", rows("drools-rule", 10)));
		collector.register("run-4", work.resolve("nothing.parquet"));
		collector.assemble("run-4");
		assertNull(uploaded, "nothing should have been uploaded");
	}

	// An assertion with no failures contributes no rows, and a fragment with no
	// rows is a file nobody needs.
	@Test
	void stagesNothingForAValidatorThatFoundNoFailures() {
		assertNull(collector.writeFragment("drools", List.of()));
		assertNull(collector.writeFragment("drools", null));
	}

	// Temp files in the work directory outlive the run that made them, and a
	// nightly makes one every day.
	@Test
	void deletesTheFragmentsItAssembled() throws Exception {
		Path drools = collector.writeFragment("drools", rows("drools-rule", 10));
		collector.register("run-5", drools);
		collector.register("run-5", sqlFragment(10));
		collector.assemble("run-5");

		assertFalse(Files.exists(drools), "the fragment should be gone once assembled");
		assertEquals(0, collector.stagedCount("run-5"));
	}

	@Test
	void deletesTheFragmentsOfAnAbandonedRun() throws Exception {
		Path drools = collector.writeFragment("drools", rows("drools-rule", 10));
		collector.register("run-6", drools);
		collector.discard("run-6");

		assertFalse(Files.exists(drools));
		assertEquals(0, collector.stagedCount("run-6"));
		assertNull(uploaded);
	}

	// A run with Drools and MRCM off has one fragment, which is the common case,
	// and unioning a single file through DuckDB to produce an identical one
	// would be pure cost.
	@Test
	void uploadsASingleFragmentWithoutRewritingIt() throws Exception {
		collector.register("run-7", sqlFragment(25));
		collector.assemble("run-7");
		assertEquals(25, countIn(uploaded, "sql-assertion"));
	}

	// A Drools finding on a description has no concept id of its own, and 0
	// would be a real sctid belonging to something else.
	@Test
	void leavesAnAbsentConceptIdNullRatherThanZero() throws Exception {
		List<FailureArchiveRow> rows = List.of(
				new FailureArchiveRow(1L, "drools-rule", null, "a description is wrong", "300000000"));
		collector.register("run-8", collector.writeFragment("drools", rows));
		collector.assemble("run-8");

		try (Connection c = DriverManager.getConnection("jdbc:duckdb:");
				PreparedStatement ps = c.prepareStatement(
						"SELECT concept_id, component_id FROM read_parquet(?)")) {
			ps.setString(1, uploaded.toString());
			try (ResultSet rs = ps.executeQuery()) {
				assertTrue(rs.next());
				rs.getLong(1);
				assertTrue(rs.wasNull(), "an absent concept id must be null, not 0");
				assertEquals("300000000", rs.getString(2));
			}
		}
	}

	@Test
	void readsAnSctidAndRefusesAnythingElse() {
		assertEquals(Long.valueOf(123456789L), FailureArchiveRow.conceptIdOf("123456789"));
		assertEquals(Long.valueOf(123456789L), FailureArchiveRow.conceptIdOf("  123456789 "));
		assertNull(FailureArchiveRow.conceptIdOf(null));
		assertNull(FailureArchiveRow.conceptIdOf(""));
		assertNull(FailureArchiveRow.conceptIdOf("not-an-sctid"));
	}
}
