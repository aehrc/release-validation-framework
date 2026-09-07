package org.ihtsdo.rvf.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;

/**
 * Reads a run's complete failure archive.
 *
 * <p>A report keeps only {@code failureExportMax} instances per assertion, so it
 * can say "40,000 concepts failed" and not "which 40,000". The rows themselves
 * are archived per run as {@code failures.parquet} - written straight out of the
 * engine's {@code qa_result} - and until now nothing read them back.
 *
 * <p>The archive lives in the job store, which may be a file share or a cloud
 * bucket, so it is staged to a local temp file first: DuckDB has to open it as a
 * file, and streaming it twice over a network share to answer one request is
 * worse than spending the disk once.
 */
@Service
public class FailureExportService {

	private static final Logger LOGGER = LoggerFactory.getLogger(FailureExportService.class);

	/** Rows fetched per round trip. Bounded so a million-row export is streamed. */
	private static final int FETCH_SIZE = 10_000;

	@Autowired
	private ValidationReportService reportService;

	@Value("${rvf.duck.work.directory:}")
	private String workDirectory;

	/**
	 * Copies the run's archive out of the job store to a local file.
	 *
	 * @return the staged file, or {@code null} if that run has no archive -
	 *         which is the normal case for a run that predates archiving, or one
	 *         where the best-effort archive write failed
	 */
	public File stageArchive(String storageLocation) throws IOException {
		try (InputStream in = reportService.getFailureArchive(storageLocation)) {
			if (in == null) {
				return null;
			}
			Path dir = workDirectory == null || workDirectory.isBlank()
					? null : Path.of(workDirectory);
			Path staged = dir != null && Files.isDirectory(dir)
					? Files.createTempFile(dir, "failures-", ".parquet")
					: Files.createTempFile("failures-", ".parquet");
			Files.copy(in, staged, StandardCopyOption.REPLACE_EXISTING);
			return staged.toFile();
		}
	}

	/**
	 * Streams the archive out as CSV, optionally for one assertion only.
	 *
	 * <p>Written row by row from a bounded fetch rather than collected: the point
	 * of the endpoint is the runs whose failure set does not fit in a report, so
	 * it must not require it to fit in memory either.
	 */
	public void writeCsv(File archive, String assertionId, OutputStream out) throws IOException {
		String sql = "SELECT * FROM read_parquet(?)"
				+ (assertionId == null || assertionId.isBlank() ? "" : " WHERE assertion_id = ?");
		try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
				PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setFetchSize(FETCH_SIZE);
			statement.setString(1, archive.getAbsolutePath());
			if (assertionId != null && !assertionId.isBlank()) {
				statement.setString(2, assertionId);
			}
			try (ResultSet rows = statement.executeQuery();
					Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
				ResultSetMetaData meta = rows.getMetaData();
				int columns = meta.getColumnCount();
				for (int i = 1; i <= columns; i++) {
					writer.write(csv(meta.getColumnLabel(i)));
					writer.write(i == columns ? "\n" : ",");
				}
				long written = 0;
				while (rows.next()) {
					for (int i = 1; i <= columns; i++) {
						writer.write(csv(rows.getString(i)));
						writer.write(i == columns ? "\n" : ",");
					}
					if (++written % FETCH_SIZE == 0) {
						// Flushed periodically so a slow client sees progress and
						// the buffer cannot grow with the result set.
						writer.flush();
					}
				}
				writer.flush();
				LOGGER.info("Exported {} failure rows from {}{}", written, archive.getName(),
						assertionId == null || assertionId.isBlank() ? "" : " for assertion " + assertionId);
			}
		} catch (SQLException e) {
			throw new IOException("Could not read the failure archive " + archive.getName(), e);
		}
	}

	/** RFC 4180: quote when the value contains a delimiter, quote or newline. */
	private static String csv(String value) {
		if (value == null) {
			return "";
		}
		if (value.indexOf(',') < 0 && value.indexOf('"') < 0
				&& value.indexOf('\n') < 0 && value.indexOf('\r') < 0) {
			return value;
		}
		return '"' + value.replace("\"", "\"\"") + '"';
	}
}
