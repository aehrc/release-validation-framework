package org.ihtsdo.rvf.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Gathers one run's failure rows from every validator into a single archive.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@code failures.parquet} used to be written by the SQL path alone, straight
 * out of the engine's {@code qa_result}, because that is the only table SQL
 * assertion execution writes to. Drools and MRCM never touch it: they build
 * {@code TestRunItem}s in memory, keep {@code failureExportMax} instances as the
 * report's sample, and drop the rest.
 *
 * <p>So the archive covered one test type of three. The uncapped export added in
 * 45262e49 reads that archive, and the report UI offers "all N as CSV" beside
 * every failure - including Drools and MRCM ones, where it filtered on an
 * assertion id present in no row and handed back a bare header. A file with a
 * header and no rows reads as "no failures" when the report on screen says
 * 5,158.
 *
 * <h2>Why a collector rather than one more COPY</h2>
 *
 * <p>The three validators run in PARALLEL - see
 * {@code ValidationRunner.runValidationTasks}, which submits each to an executor
 * and merges the reports afterwards. The SQL task used to write and upload the
 * archive before the other two had finished, so there was no point at which one
 * statement could see all three sets of rows.
 *
 * <p>Each validator therefore writes its own rows to a LOCAL parquet fragment
 * while it still holds them, and registers it here. After the merge, {@link
 * #assemble} unions the fragments into one file and uploads that. The run
 * database's connection lifetime is untouched, which is what makes this safe to
 * do inside a parallel phase.
 *
 * <h2>What it costs</h2>
 *
 * <p>Measured with {@code bench/ArchiveBench.java} at 1,000,000 SQL rows and
 * 200,000 Drools and MRCM rows - a bad night; the AU nightly's largest single
 * Drools rule reports 144,212 instances on its own:
 *
 * <pre>
 *   COPY qa_result to parquet (the old whole job)      44 ms
 *   Appending 200,000 rows                            256 ms
 *   Union into one parquet                             68 ms
 * </pre>
 *
 * <p>About 280ms added to a run measured in hundreds of seconds. Two details
 * earn that number and are not incidental:
 *
 * <ul>
 *   <li><b>The appender, not a JDBC batch.</b> The same 200,000 rows through a
 *       prepared-statement batch took 22,124 ms - 86x slower, and 460x the cost
 *       of the COPY it feeds. Both are in the benchmark so the choice stays
 *       justified.</li>
 *   <li><b>Raw fields only.</b> {@code getAdditionalFields} enrichment stays
 *       capped at {@code failureExportMax} for the report's sample, exactly as
 *       before. It exists to feed whitelist matching, and a run with the
 *       whitelist disabled already produces failures without it, so it has no
 *       place in an archive whose columns should not depend on that setting.</li>
 * </ul>
 *
 * <p>Best-effort throughout, like the write it replaces: a validation that ran
 * and reported is not failed by an archive that could not be written.
 */
@Service
public class FailureArchiveCollector {

	private static final Logger LOGGER = LoggerFactory.getLogger(FailureArchiveCollector.class);

	/** RVF's qa_result, as DuckDbValidationService.createResultTable declares it. */
	static final String FRAGMENT_DDL = "CREATE TABLE rows_ ("
			+ "id BIGINT, run_id BIGINT, assertion_id VARCHAR, concept_id BIGINT, "
			+ "details VARCHAR, component_id VARCHAR, table_name VARCHAR, "
			+ "skip_module_check BOOLEAN)";

	@Autowired
	private ValidationReportService reportService;

	@Value("${rvf.duck.work.directory:}")
	private String workDirectory;

	@Value("${rvf.duck.archive.failures:true}")
	private boolean archiveFailures;

	/**
	 * Fragments per run, keyed on the storage location.
	 *
	 * <p>Concurrent because the whole point is that three validator threads
	 * register into it at once, and a synchronized list per run because a
	 * validator may contribute more than one fragment - the extension split runs
	 * two SQL phases.
	 */
	private final Map<String, List<Path>> fragments = new ConcurrentHashMap<>();

	/** Spring's constructor: the fields are injected. */
	public FailureArchiveCollector() {
	}

	/**
	 * Explicit construction, for tests and for any caller assembling one by hand.
	 *
	 * <p>Public because the validators that register into this live in
	 * {@code ...service.duck} and their tests do too, so package-private setters
	 * would have made every one of them reach through a helper.
	 */
	public FailureArchiveCollector(ValidationReportService reportService, String workDirectory,
			boolean archiveFailures) {
		this.reportService = reportService;
		this.workDirectory = workDirectory;
		this.archiveFailures = archiveFailures;
	}

	/** Whether archiving is on at all, so callers can skip the work entirely. */
	public boolean isEnabled() {
		return archiveFailures;
	}

	/** Adds one validator's rows to this run's archive. */
	public void register(String storageLocation, Path fragment) {
		if (!archiveFailures || storageLocation == null || fragment == null) {
			return;
		}
		fragments.computeIfAbsent(storageLocation, k -> java.util.Collections.synchronizedList(new ArrayList<>()))
				.add(fragment);
	}

	/**
	 * A local parquet holding these rows, ready to {@link #register}.
	 *
	 * <p>Returns null rather than throwing: this is a convenience beside the
	 * report, and a validator that cannot write its fragment must still report
	 * what it found.
	 */
	public Path writeFragment(String name, List<FailureArchiveRow> rows) {
		if (!archiveFailures || rows == null || rows.isEmpty()) {
			return null;
		}
		Path fragment = null;
		try {
			fragment = tempFile(name);
			try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
					Statement st = connection.createStatement()) {
				st.execute(FRAGMENT_DDL);
				FailureArchiveRow.appendAll(connection, "rows_", rows);
				st.execute("COPY (SELECT * FROM rows_) TO '" + fragment
						+ "' (FORMAT PARQUET, COMPRESSION ZSTD)");
			}
			LOGGER.info("Staged {} failure rows for the archive as {}", rows.size(), fragment.getFileName());
			return fragment;
		} catch (Exception e) {
			LOGGER.warn("Could not stage {} failure rows for the archive: {}", rows.size(), e.toString());
			deleteQuietly(fragment);
			return null;
		}
	}

	/**
	 * Unions this run's fragments into {@code failures.parquet} and uploads it.
	 *
	 * <p>Called once, after the validators have been merged. Always clears the
	 * run's fragments and deletes them, including on failure, because a run that
	 * never assembles must not leak parquet files into the work directory.
	 */
	public void assemble(String storageLocation) {
		List<Path> staged = fragments.remove(storageLocation);
		if (!archiveFailures || staged == null || staged.isEmpty()) {
			return;
		}
		Path combined = null;
		try {
			List<Path> present = staged.stream().filter(Files::isReadable).collect(Collectors.toList());
			if (present.isEmpty()) {
				return;
			}
			// One fragment is the common case - a run with Drools and MRCM off -
			// and copying it through DuckDB to produce an identical file would be
			// pure cost. Upload it as it stands.
			if (present.size() == 1) {
				upload(storageLocation, present.get(0));
				return;
			}
			combined = tempFile("failures-combined");
			String sources = present.stream()
					.map(p -> "'" + p + "'")
					.collect(Collectors.joining(", ", "[", "]"));
			try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
					Statement st = connection.createStatement()) {
				// union_by_name, because a fragment written by an older build may
				// not have the same column order and a positional union would
				// silently interleave the columns.
				st.execute("COPY (SELECT * FROM read_parquet(" + sources + ", union_by_name = true)) TO '"
						+ combined + "' (FORMAT PARQUET, COMPRESSION ZSTD)");
			}
			upload(storageLocation, combined);
		} catch (Exception e) {
			LOGGER.warn("Could not assemble the failure archive for {}: {}", storageLocation, e.toString());
		} finally {
			deleteQuietly(combined);
			staged.forEach(FailureArchiveCollector::deleteQuietly);
		}
	}

	/** Drops a run's fragments without uploading, for a run that was abandoned. */
	public void discard(String storageLocation) {
		List<Path> staged = fragments.remove(storageLocation);
		if (staged != null) {
			staged.forEach(FailureArchiveCollector::deleteQuietly);
		}
	}

	private void upload(String storageLocation, Path file) throws IOException {
		reportService.writeFailureArchive(storageLocation, file.toFile());
		LOGGER.info("Archived failure detail to {}failures.parquet ({} KB)",
				storageLocation, Files.size(file) / 1024);
	}

	private Path tempFile(String name) throws IOException {
		Path dir = workDirectory == null || workDirectory.isBlank() ? null : Path.of(workDirectory);
		return dir != null && Files.isDirectory(dir)
				? Files.createTempFile(dir, name + "-", ".parquet")
				: Files.createTempFile(name + "-", ".parquet");
	}

	private static void deleteQuietly(Path path) {
		if (path == null) {
			return;
		}
		try {
			Files.deleteIfExists(path);
		} catch (IOException e) {
			LOGGER.debug("Could not delete {}: {}", path, e.toString());
		}
	}

	/** Visible for tests: how many fragments are held for a run right now. */
	public int stagedCount(String storageLocation) {
		List<Path> staged = fragments.get(storageLocation);
		return staged == null ? 0 : staged.size();
	}

	public void setArchiveFailures(boolean archiveFailures) {
		this.archiveFailures = archiveFailures;
	}

	public void setReportService(ValidationReportService reportService) {
		this.reportService = reportService;
	}

	public void setWorkDirectory(String workDirectory) {
		this.workDirectory = workDirectory;
	}
}
