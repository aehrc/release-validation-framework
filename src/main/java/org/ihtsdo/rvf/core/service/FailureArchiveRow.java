package org.ihtsdo.rvf.core.service;

import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * One archived failing row, in the shape {@code qa_result} already holds.
 *
 * <p>The columns are RVF's own and are not chosen here: an SQL assertion's rows
 * are written to {@code qa_result} by the engine, and a Drools or MRCM row has
 * to look the same or a consumer would need to know which validator produced a
 * row before it could read it. {@code GET /result/&#123;runId&#125;/failures} filters on
 * {@code assertion_id} alone, and so does the NCTS indexer server's failure
 * dialog.
 *
 * <p>{@code table_name} is empty for Drools and MRCM: neither finding belongs to
 * one RF2 file. {@code skipModuleCheck} is false, which is what the module check
 * means for a finding that never went through it.
 *
 * <p>Deliberately raw. There is no {@code fullComponent} here, because that
 * field exists to feed whitelist matching - {@code DuckFailuresExtractor} builds
 * a {@code WhitelistItem} out of it - and a run with the whitelist disabled
 * already produces failures without it. An archive whose columns depend on
 * whether the whitelist was on would be worse than one that never carries it.
 */
public record FailureArchiveRow(
		long runId,
		String assertionId,
		Long conceptId,
		String details,
		String componentId) {

	/**
	 * Appends every row through DuckDB's appender.
	 *
	 * <p>The appender rather than a prepared-statement batch, and that is
	 * measured rather than assumed: {@code bench/ArchiveBench.java} puts 200,000
	 * rows in at 256 ms this way against 22,124 ms as a JDBC batch - 86x - which
	 * is the difference between a quarter of a second and a stall long enough to
	 * notice on a run the team has been optimising in whole seconds.
	 */
	public static void appendAll(Connection connection, String table, List<FailureArchiveRow> rows)
			throws SQLException {
		DuckDBConnection duck = connection.unwrap(DuckDBConnection.class);
		try (DuckDBAppender appender = duck.createAppender("main", table)) {
			long id = 0;
			for (FailureArchiveRow row : rows) {
				appender.beginRow();
				appender.append(id++);
				appender.append(row.runId());
				appender.append(row.assertionId() == null ? "" : row.assertionId());
				// A Drools finding on a description or a relationship has no
				// concept id of its own, and 0 would be a real sctid that is not
				// this row's. The column stays null.
				if (row.conceptId() == null) {
					appender.appendNull();
				} else {
					appender.append(row.conceptId().longValue());
				}
				appender.append(row.details() == null ? "" : row.details());
				appender.append(row.componentId() == null ? "" : row.componentId());
				appender.append("");
				appender.append(false);
				appender.endRow();
			}
		}
	}

	/** Parses an sctid, or null when the value is absent or not one. */
	public static Long conceptIdOf(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Long.valueOf(value.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}
}
