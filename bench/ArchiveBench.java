import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

/**
 * What archiving Drools and MRCM failures alongside the SQL ones costs.
 *
 * <p>Today {@code DuckDbValidationService.archiveFailures} writes ONE table -
 * {@code qa_result} - and only SQL assertion execution writes rows there. A
 * Drools or MRCM failure is therefore absent from {@code failures.parquet}, and
 * the uncapped export endpoint added in 45262e49 returns a bare header for one.
 *
 * <p>Getting those rows in costs something, and this measures what, so the
 * design is chosen on numbers rather than on a guess. Four phases, matching the
 * four things the change actually does:
 *
 * <ol>
 *   <li>BASELINE - COPY qa_result to parquet, exactly as the code does now.</li>
 *   <li>APPEND - write the Drools and MRCM rows into a DuckDB table with the
 *       appender, which is the new work per row.</li>
 *   <li>UNION - COPY the union of the SQL table and the new rows into one
 *       parquet, which is the single-file design.</li>
 *   <li>FRAGMENT - COPY the new rows to their own parquet, which is the
 *       separate-file design. The comparison against UNION is the whole
 *       question.</li>
 * </ol>
 *
 * <p>Row counts default to a bad night measured on the deployed server: 1.0M SQL
 * rows, and 200k non-SQL rows - the AU nightly's largest single Drools rule
 * alone reports 144,212 instances.
 *
 *     javac -cp "$(cat bench/duckdb-cp.txt)" -d /tmp/bench bench/ArchiveBench.java
 *     java -cp "/tmp/bench:$(cat bench/duckdb-cp.txt)" ArchiveBench [sqlRows] [otherRows]
 */
public class ArchiveBench {

	/** RVF's qa_result, as DuckDbValidationService.createResultTable declares it. */
	private static final String DDL = "CREATE TABLE %s ("
			+ "id BIGINT, run_id BIGINT, assertion_id VARCHAR, concept_id BIGINT, "
			+ "details VARCHAR, component_id VARCHAR, table_name VARCHAR, "
			+ "skip_module_check BOOLEAN)";

	public static void main(String[] args) throws Exception {
		long sqlRows = args.length > 0 ? Long.parseLong(args[0]) : 1_000_000L;
		long otherRows = args.length > 1 ? Long.parseLong(args[1]) : 200_000L;
		Path dir = Files.createTempDirectory("archive-bench-");
		System.out.printf("qa_result rows: %,d   Drools+MRCM rows: %,d%n%n", sqlRows, otherRows);

		try (Connection c = DriverManager.getConnection("jdbc:duckdb:")) {
			try (Statement st = c.createStatement()) {
				st.execute(String.format(DDL, "qa_result"));
				// range() rather than a row-by-row insert: the point of the
				// baseline is the COPY, not how the rows got there.
				st.execute("INSERT INTO qa_result SELECT i, 1788693845351, "
						+ "'a' || (i % 500), 100000000 + i, "
						+ "'concept ' || i || ' violates the assertion', "
						+ "'' || (200000000 + i), 'sct2_Concept', false "
						+ "FROM range(" + sqlRows + ") t(i)");
			}

			Path baseline = dir.resolve("failures.parquet");
			long t0 = System.nanoTime();
			copy(c, "SELECT * FROM qa_result", baseline);
			long baselineMs = ms(t0);
			System.out.printf("1. BASELINE  COPY qa_result -> parquet        %6d ms   %s%n",
					baselineMs, size(baseline));

			// The new per-row work: every Drools and MRCM failure appended to a
			// table in the same shape, built from the raw fields the services
			// already hold. No enrichment - getAdditionalFields stays capped at
			// failureExportMax for the report's sample, as it is today.
			try (Statement st = c.createStatement()) {
				st.execute(String.format(DDL, "other_failures"));
				st.execute(String.format(DDL, "other_failures_batch"));
			}
			// Two ways to get the same rows in, because the first one measured
			// was 460x the cost of the COPY it feeds and that is a design
			// decision, not a detail.
			t0 = System.nanoTime();
			insertByBatch(c, otherRows);
			long batchMs = ms(t0);
			System.out.printf("2a. JDBC batch  %,d Drools/MRCM rows     %6d ms%n", otherRows, batchMs);

			t0 = System.nanoTime();
			insertByAppender(c, otherRows);
			long appendMs = ms(t0);
			System.out.printf("2b. Appender    %,d Drools/MRCM rows     %6d ms   (%.0fx faster)%n",
					otherRows, appendMs, batchMs / (double) Math.max(1, appendMs));

			Path unioned = dir.resolve("failures-union.parquet");
			t0 = System.nanoTime();
			copy(c, "SELECT * FROM qa_result UNION ALL SELECT * FROM other_failures", unioned);
			long unionMs = ms(t0);
			System.out.printf("3. UNION     one parquet, all test types      %6d ms   %s%n",
					unionMs, size(unioned));

			Path fragment = dir.resolve("other-failures.parquet");
			t0 = System.nanoTime();
			copy(c, "SELECT * FROM other_failures", fragment);
			long fragmentMs = ms(t0);
			System.out.printf("4. FRAGMENT  separate parquet for the new rows %6d ms   %s%n",
					fragmentMs, size(fragment));

			System.out.println();
			System.out.printf("Single file : %d ms against a %d ms baseline  (+%d ms, +%.0f%%)%n",
					appendMs + unionMs, baselineMs, appendMs + unionMs - baselineMs,
					100.0 * (appendMs + unionMs - baselineMs) / baselineMs);
			System.out.printf("Two files   : %d ms against a %d ms baseline  (+%d ms, +%.0f%%)%n",
					baselineMs + appendMs + fragmentMs, baselineMs, appendMs + fragmentMs,
					100.0 * (appendMs + fragmentMs) / baselineMs);

			// Reading one assertion back is what the export endpoint and the
			// indexer server's failure dialog both do, so it is measured too.
			t0 = System.nanoTime();
			long found = countOne(c, unioned, "a1");
			System.out.printf("%nRead one assertion from the union: %,d rows in %d ms%n", found, ms(t0));
		} finally {
			deleteTree(dir);
		}
	}

	/**
	 * The obvious way, and the wrong one. Kept in the benchmark because the
	 * number is the argument: a prepared-statement batch is bounded by
	 * per-row JDBC overhead that the appender does not pay.
	 */
	private static void insertByBatch(Connection c, long rows) throws Exception {
		try (PreparedStatement ps = c.prepareStatement(
				"INSERT INTO other_failures_batch VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
			for (long i = 0; i < rows; i++) {
				ps.setLong(1, i);
				ps.setLong(2, 1788693845351L);
				ps.setString(3, "fbd4bbb5-3e62-4ccb-824a-e82d9771c0ee");
				ps.setLong(4, 100000000L + i);
				ps.setString(5, "An active term should not contain tabs, newlines, or characters @, $, #, \\.");
				ps.setString(6, "" + (300000000L + i));
				ps.setNull(7, java.sql.Types.VARCHAR);
				ps.setBoolean(8, false);
				ps.addBatch();
				if (i % 10_000 == 0) {
					ps.executeBatch();
				}
			}
			ps.executeBatch();
		}
	}

	/**
	 * DuckDB's appender: rows go straight into the column chunks with no SQL
	 * parse, no plan and no per-row round trip. This is what the production
	 * code should use.
	 */
	private static void insertByAppender(Connection c, long rows) throws Exception {
		DuckDBConnection duck = c.unwrap(DuckDBConnection.class);
		try (DuckDBAppender appender = duck.createAppender("main", "other_failures")) {
			for (long i = 0; i < rows; i++) {
				appender.beginRow();
				appender.append(i);
				appender.append(1788693845351L);
				appender.append("fbd4bbb5-3e62-4ccb-824a-e82d9771c0ee");
				appender.append(100000000L + i);
				appender.append("An active term should not contain tabs, newlines, or characters @, $, #, \\.");
				appender.append("" + (300000000L + i));
				appender.append("");
				appender.append(false);
				appender.endRow();
			}
		}
	}

	private static void copy(Connection c, String select, Path out) throws Exception {
		try (Statement st = c.createStatement()) {
			st.execute("COPY (" + select + ") TO '" + out + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
		}
	}

	private static long countOne(Connection c, Path parquet, String assertionId) throws Exception {
		try (PreparedStatement ps = c.prepareStatement(
				"SELECT COUNT(*) FROM read_parquet(?) WHERE assertion_id = ?")) {
			ps.setString(1, parquet.toString());
			ps.setString(2, assertionId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getLong(1) : -1;
			}
		}
	}

	private static long ms(long fromNanos) {
		return (System.nanoTime() - fromNanos) / 1_000_000;
	}

	private static String size(Path p) throws Exception {
		return String.format("%,d KB", Files.size(p) / 1024);
	}

	private static void deleteTree(Path dir) {
		File[] files = dir.toFile().listFiles();
		if (files != null) {
			for (File f : files) {
				f.delete();
			}
		}
		dir.toFile().delete();
	}
}
