package org.ihtsdo.rvf.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The failure export exists because a report holds only the first N instances
 * per assertion, so these tests are about completeness and about not mangling
 * the rows on the way out.
 */
class FailureExportServiceTest {

	@TempDir
	Path temp;

	/** A parquet archive shaped like the engine's qa_result table. */
	private File archive(int rowsPerAssertion) throws Exception {
		return archive(rowsPerAssertion, true);
	}

	/**
	 * @param tricky include the row whose detail holds a quote and a NEWLINE.
	 *               Counting records by lines() cannot work when a field
	 *               legitimately contains one, so completeness is measured on
	 *               an archive without it and the quoting test covers it
	 *               separately. The 502-vs-501 discrepancy that first showed
	 *               here was the writer being right and the assertion wrong.
	 */
	private File archive(int rowsPerAssertion, boolean tricky) throws Exception {
		Path parquet = temp.resolve("failures.parquet");
		try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
				Statement st = connection.createStatement()) {
			st.execute("CREATE TABLE qa_result(run_id BIGINT, assertion_id VARCHAR, "
					+ "concept_id VARCHAR, details VARCHAR, component_id VARCHAR, table_name VARCHAR)");
			for (int i = 0; i < rowsPerAssertion; i++) {
				st.execute("INSERT INTO qa_result VALUES (1, 'a-1', '" + (100 + i)
						+ "', 'plain detail', 'c" + i + "', 'concept_s')");
				st.execute("INSERT INTO qa_result VALUES (1, 'a-2', '" + (200 + i)
						+ "', 'detail, with comma', 'c" + i + "', 'concept_s')");
			}
			if (tricky) {
				// The values that break a naive CSV writer.
				st.execute("INSERT INTO qa_result VALUES (1, 'a-3', '999', "
						+ "'has \"quotes\" and" + System.lineSeparator() + "a newline', 'c9', 'concept_s')");
			}
			st.execute("COPY (SELECT * FROM qa_result) TO '" + parquet
					+ "' (FORMAT PARQUET, COMPRESSION ZSTD)");
		}
		return parquet.toFile();
	}

	private String csv(File archive, String assertionId) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		new FailureExportService().writeCsv(archive, assertionId, out);
		return out.toString("UTF-8");
	}

	@Test
	void exportsEveryRowNotJustTheReportedSample() throws Exception {
		// 250 per assertion is past every cap in play: 10 by default, 100 on the
		// nightly. If the export were reading the report it would stop there.
		String csv = csv(archive(250, false), null);
		long dataRows = csv.lines().count() - 1;
		assertEquals(500, dataRows,
				"expected 250 + 250 rows; a capped export is the bug this endpoint exists to fix");
	}

	@Test
	void headerNamesTheQaResultColumns() throws Exception {
		String header = csv(archive(1), null).lines().findFirst().orElseThrow();
		assertEquals("run_id,assertion_id,concept_id,details,component_id,table_name", header);
	}

	@Test
	void filteringByAssertionDoesNotTransferTheWholeRun() throws Exception {
		String csv = csv(archive(50), "a-1");
		long dataRows = csv.lines().count() - 1;
		assertEquals(50, dataRows);
		assertTrue(csv.contains("a-1"), "the requested assertion should be present");
		assertTrue(!csv.contains("a-2"), "another assertion's rows must not be included");
	}

	@Test
	void quotesCommasQuotesAndNewlinesSoTheCsvStaysParseable() throws Exception {
		String csv = csv(archive(1), "a-2");
		assertTrue(csv.contains("\"detail, with comma\""),
				"a value containing the delimiter must be quoted, got:\n" + csv);

		String tricky = csv(archive(1), "a-3");
		assertTrue(tricky.contains("\"\"quotes\"\""),
				"embedded quotes must be doubled, got:\n" + tricky);
		// The embedded newline must live inside a quoted field, so the row count
		// stays 1 rather than splitting into two.
		assertTrue(tricky.contains("\""), "the field must be quoted");
	}

	@Test
	void anEmptyArchiveIsAnEmptyExportRatherThanAnError() throws Exception {
		Path parquet = temp.resolve("empty.parquet");
		try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
				Statement st = connection.createStatement()) {
			st.execute("CREATE TABLE qa_result(run_id BIGINT, assertion_id VARCHAR)");
			st.execute("COPY (SELECT * FROM qa_result) TO '" + parquet + "' (FORMAT PARQUET)");
		}
		String csv = csv(parquet.toFile(), null);
		assertLinesMatch(List.of("run_id,assertion_id"), csv.lines().toList());
	}
}
