package org.ihtsdo.rvf.core.service.duck;

import org.apache.commons.io.FileUtils;
import org.ihtsdo.otf.rest.client.RestClientException;
import org.ihtsdo.otf.utils.ExceptionUtils;
import org.ihtsdo.rvf.config.ExecutionEngine;
import org.ihtsdo.rvf.core.data.model.Assertion;
import org.ihtsdo.rvf.core.data.model.SeverityLevel;
import org.ihtsdo.rvf.core.data.model.TestRunItem;
import org.ihtsdo.rvf.core.data.model.TestType;
import org.ihtsdo.rvf.core.data.model.ValidationReport;
import org.ihtsdo.rvf.core.service.ValidationReportService;
import org.ihtsdo.rvf.core.service.ValidationVersionLoader;
import org.ihtsdo.rvf.core.service.WhitelistService;
import org.ihtsdo.rvf.core.service.config.MysqlExecutionConfig;
import org.ihtsdo.rvf.core.service.config.ValidationRunConfig;
import org.ihtsdo.rvf.core.service.pojo.ValidationStatusReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Runs a validation on DuckDB - the counterpart of
 * {@link org.ihtsdo.rvf.core.service.MysqlValidationService#runRF2MysqlValidations}.
 *
 * <p>The sequence is that method's, step for step: load the releases, create
 * qa_result, prepare the schema, select the assertions the requested groups
 * name, execute them, read qa_result back, and shape the answer into a
 * {@link ValidationStatusReport}. Everything it orchestrates already exists in
 * this package; this class is the order they go in and the error handling
 * between them.
 *
 * <p>Two differences from the MySQL service are structural rather than
 * incidental:
 *
 * <ul>
 * <li><b>It takes release DIRECTORIES, not a config to resolve them from.</b>
 *     {@link ValidationVersionLoader} splits in two - an ACQUISITION half
 *     (download the prospective package, the previous release, the dependency
 *     releases) that is engine-agnostic, and a LOADING half that
 *     {@link DuckMaterialiser} replaces. Only the loading half is reimplemented
 *     here, so the acquired paths arrive as an argument. That is what lets a CI
 *     pipeline run a full extension validation against PINNED inputs: RVF's
 *     normal route resolves a dependency through ModuleStorageCoordinator, whose
 *     production configuration we cannot currently verify, and a validation that
 *     silently picked a different dependency would report differences that are
 *     not in the release.
 * <li><b>No per-run schemas to drop and no qa_result to truncate.</b> The MySQL
 *     service starts by dropping the previous run's schemas and truncating a
 *     shared result table, because both live in a long-lived server. Here the
 *     whole database is one file created for this run, so cleanup is deleting
 *     it - which also means a crashed run leaks a file rather than leaving
 *     schemas the NEXT run has to know to remove.
 * </ul>
 *
 * <p>Not reproduced: {@code runExtensionReleaseValidation}'s two-phase split,
 * where release-type assertions run against the extension alone and the
 * international snapshot is then merged INTO the prospective schema for the
 * rest. That merge exists because a MySQL assertion addresses one schema at a
 * time; here the dependency is a schema of its own that {@code <DEPENDENCY>}
 * names directly, so there is nothing to merge. Production's AU run does not
 * exercise the MySQL split either - {@code releaseAsAnEdition=true} means no
 * dependency is loaded and the branch is never taken. An extension validation
 * with {@code releaseAsAnEdition=false} is therefore the one shape this class
 * has not been shown to match, and it should be compared against MySQL before
 * it is trusted there.
 */
@Service
@ConditionalOnProperty(name = ExecutionEngine.PROPERTY, havingValue = ExecutionEngine.DUCKDB)
public class DuckDbValidationService {

	private static final Logger LOGGER = LoggerFactory.getLogger(DuckDbValidationService.class);

	/**
	 * The schema names {@link DuckBinder} resolves {@code <PROSPECTIVE>},
	 * {@code <PREVIOUS>} and {@code <DEPENDENCY>} to. They are not release names
	 * and must not be made unique per run: the store's sentinels were compiled
	 * against these literals, and rvf_duck.py attaches under the same three.
	 */
	private static final String PROSPECTIVE_SCHEMA = "prospective";
	private static final String PREVIOUS_SCHEMA = "previous";
	private static final String DEPENDENCY_SCHEMA = "dependency";

	/** Where qa_result lives, matching DuckStoreProbe and rvf_duck.py. */
	private static final String QA_RESULT_SCHEMA = "rvf_results";

	/** Same text MysqlValidationService writes, so the progress file reads alike. */
	private static final String START_EXECUTING_ASSERTIONS = "Start executing assertions...";

	/**
	 * Assertions per progress message. Mirrors MysqlValidationService's "every
	 * 10 assertions", and is also the batch handed to
	 * {@link DuckDbAssertionExecutionService#execute} - one call per assertion
	 * would rebuild the store's assertion index 450-odd times.
	 */
	private static final int PROGRESS_EVERY = 10;

	/** Assertions that build shared tables rather than validating anything. */
	private static final String RESOURCE_KEYWORD = "resource";

	private static final String SQL_SUMMARY_KEY = TestType.SQL.name();

	/** The effectiveTime suffix RVF reads a release's version from. */
	private static final Pattern RF2_VERSION = Pattern.compile(".*_(\\d+)\\.txt");

	private final ValidationReportService reportService;
	private final WhitelistService whitelistService;
	private final String storeFile;
	private final String corpusRoot;
	private final String workDirectory;
	private final String qaResultTable;

	public DuckDbValidationService(ValidationReportService reportService,
			WhitelistService whitelistService,
			@Value("${rvf.duck.store:}") String storeFile,
			@Value("${rvf.assertion.resource.local.path:}") String corpusRoot,
			@Value("${rvf.duck.work.directory:${java.io.tmpdir}}") String workDirectory,
			@Value("${rvf.qa.result.table.name:qa_result}") String qaResultTableName) {
		this.reportService = reportService;
		this.whitelistService = whitelistService;
		this.storeFile = storeFile;
		this.corpusRoot = corpusRoot;
		this.workDirectory = workDirectory;
		this.qaResultTable = QA_RESULT_SCHEMA + "." + qaResultTableName;
	}

	/**
	 * The RF2 release directories a run validates - already downloaded and
	 * unzipped by whoever acquired them.
	 *
	 * <p>{@code previous} and {@code dependency} are null when the run does not
	 * hold them, and null is a MEANINGFUL value: it makes {@link DuckBinder}
	 * leave {@code <PREVIOUS>} / {@code <DEPENDENCY>} unbound, which is what
	 * turns an assertion over a release we do not have into "not run" rather
	 * than a pass. Pointing them at an empty directory instead would create
	 * empty placeholder tables, every such assertion would run against zero
	 * rows, and "nothing differs from the previous release" would be reported as
	 * a clean pass for a comparison nothing performed.
	 */
	public record ReleaseDirectories(Path prospective, Path previous, Path dependency) {

		public static ReleaseDirectories of(Path prospective) {
			return new ReleaseDirectories(prospective, null, null);
		}
	}

	/**
	 * The config-driven entry point, for a caller that already holds a
	 * {@link ValidationRunConfig} - the shape {@code ValidationRunner} would use.
	 */
	public ValidationStatusReport runRF2DuckDbValidations(ValidationRunConfig validationConfig,
			ValidationStatusReport statusReport, ReleaseDirectories releases) {
		// ValidationVersionLoader is a @ConditionalOnMysqlEngine @Service, so
		// there is no bean of it to inject here - but createExecutionConfig is
		// pure translation from one config object into another and touches none
		// of its injected fields, so constructing one with `new` reuses those
		// rules rather than keeping a second copy of them in step. Same
		// reasoning, and the same hazard, as DuckAssertionSource's
		// `new AssertionGroupImporter(null)`: if that method ever grows a call to
		// releaseDataManager it will NPE here, at run time, in DuckDB mode only.
		MysqlExecutionConfig executionConfig =
				new ValidationVersionLoader().createExecutionConfig(validationConfig);
		return runValidations(executionConfig, releases, validationConfig.getStorageLocation(),
				statusReport);
	}

	/**
	 * Runs one validation to completion and returns {@code statusReport}.
	 *
	 * <p>Never throws: every failure becomes a message on the status report, as
	 * on the MySQL path, because the caller's job is to write a report either
	 * way. What it will not do is report RESULTS alongside a failure that makes
	 * them meaningless - see the setup handling below.
	 */
	public ValidationStatusReport runValidations(MysqlExecutionConfig executionConfig,
			ReleaseDirectories releases, String reportStorage, ValidationStatusReport statusReport) {
		long timeStart = System.currentTimeMillis();
		Path database = databaseFile(executionConfig.getExecutionId());
		try {
			// A file database, not jdbc:duckdb: in memory. A full release is
			// tens of millions of rows and DuckDB only spills to disk when it
			// has a file to spill beside; an in-memory run of the real corpus
			// dies on memory rather than on anything about the release.
			//
			// Deleted first because the name is derived from the run id: a
			// previous crashed attempt at the same run would otherwise be
			// REOPENED, and its stale `prospective` schema is last week's
			// release validated under this week's run id.
			FileUtils.deleteQuietly(database.toFile());
			Class.forName("org.duckdb.DuckDBDriver");
			try (Connection connection = DriverManager.getConnection("jdbc:duckdb:" + database)) {
				return run(connection, executionConfig, releases, reportStorage, statusReport,
						timeStart);
			}
		} catch (Exception e) {
			// The outermost net: a missing store, a driver that will not load,
			// an IO error under the release directory. None of them is a
			// validation that happened, and saying so is the only honest report.
			String message = ExceptionUtils.getExceptionCause("Failed to run DuckDB validation", e);
			LOGGER.error(message, e);
			statusReport.addFailureMessage(message);
			statusReport.getReportSummary().put(SQL_SUMMARY_KEY, message);
			return statusReport;
		} finally {
			deleteDatabase(database);
		}
	}

	private ValidationStatusReport run(Connection connection, MysqlExecutionConfig executionConfig,
			ReleaseDirectories releases, String reportStorage, ValidationStatusReport statusReport,
			long timeStart) throws IOException {
		DuckStore store = DuckStore.read(Path.of(storeFile));
		LOGGER.info("DuckDB assertion store loaded from {}: {}", storeFile, store.generatorDescription());

		String lastItemLoadAttempted = "Item Unknown";
		try {
			lastItemLoadAttempted = "Prospective Release - " + releases.prospective();
			materialise(connection, releases.prospective(), PROSPECTIVE_SCHEMA, store);
			// Only now: pre-requisites.sql names its inputs unqualified, so the
			// search path has to point at a schema that already exists.
			session(connection);
			statusReport.setRF2Files(rf2FileNames(releases.prospective()));

			if (releases.previous() != null) {
				lastItemLoadAttempted = "Previous Release - " + releases.previous();
				materialise(connection, releases.previous(), PREVIOUS_SCHEMA, store);
			}
			if (releases.dependency() != null) {
				lastItemLoadAttempted = "Dependency Release - " + releases.dependency();
				materialise(connection, releases.dependency(), DEPENDENCY_SCHEMA, store);
			}
			createResultTable(connection);
		} catch (Exception e) {
			String message = ExceptionUtils.getExceptionCause(
					String.format("Failed to load data (%s) into DuckDB", lastItemLoadAttempted), e);
			LOGGER.error(message, e);
			statusReport.addFailureMessage(message);
			statusReport.getReportSummary().put(SQL_SUMMARY_KEY, message);
			return statusReport;
		}

		DuckBinder binder = new DuckBinder(store.sentinels(), new DuckBinder.Config(
				executionConfig.getExecutionId(), PROSPECTIVE_SCHEMA,
				releases.previous() == null ? null : PREVIOUS_SCHEMA,
				releases.dependency() == null ? null : DEPENDENCY_SCHEMA,
				qaResultTable, executionConfig.getDefaultModuleId(),
				executionConfig.getIncludedModules(), releaseVersion(releases.prospective())));
		DuckDbAssertionExecutionService executionService =
				new DuckDbAssertionExecutionService(store, binder, connection);

		try {
			executionService.prepareSchema();
		} catch (DuckDbAssertionExecutionService.SetupFailedException e) {
			// Deliberately not downgraded to a warning and carried past. The
			// publisher emits no setup statement it expects to fail, so a
			// failure here means the schema is half built - and that fails
			// silently: the macros lost belong to whichever corpus this run does
			// not exercise, until the day it does. A status report with no
			// results beats results nobody can account for.
			LOGGER.error("DuckDB setup failed - abandoning run {}", executionConfig.getExecutionId(), e);
			statusReport.addFailureMessage(e.getMessage());
			statusReport.getReportSummary().put(SQL_SUMMARY_KEY, e.getMessage());
			return statusReport;
		}

		DuckAssertionSource source = assertionSource(store);
		List<Assertion> assertions = selectAssertions(source, executionConfig);
		LOGGER.info("Total assertions to run {} for groups {}", assertions.size(),
				executionConfig.getGroupNames());
		List<TestRunItem> items = executeAssertions(executionService, assertions, reportStorage);

		constructTestReport(statusReport, executionConfig, timeStart, items,
				new DuckFailuresExtractor(connection, qaResultTable, whitelistService,
						source::findAll));
		return statusReport;
	}

	/**
	 * The resource assertions first, then the requested groups' - the order
	 * {@code MysqlValidationService.runAssertionTests} uses, for the reason it
	 * uses it: the {@code resource} category is infrastructure that builds the
	 * shared intermediate tables (res_edited_active_concepts, tmp_pt, ancestors)
	 * other assertions select from. Run them second and a dozen assertions fail
	 * on a table nothing had created yet.
	 *
	 * <p>De-duplicated, which the MySQL service does not do. It gets away with
	 * that because no resource assertion is in a requested group today; were one
	 * ever added, MySQL would run it twice and so would this - and a second run
	 * inserts a second copy of every finding, so the assertion's reported
	 * failure count doubles. Cheap to prevent, invisible when it happens.
	 */
	private List<Assertion> selectAssertions(DuckAssertionSource source,
			MysqlExecutionConfig executionConfig) {
		List<Assertion> resourceAssertions = source.getAssertionsByKeyWords(RESOURCE_KEYWORD, true);
		LOGGER.info("Found total resource assertions need to be run before test {}",
				resourceAssertions.size());

		List<Assertion> selected = new ArrayList<>(resourceAssertions);
		Set<UUID> already = new LinkedHashSet<>();
		resourceAssertions.forEach(a -> already.add(a.getUuid()));
		List<String> groupNames = executionConfig.getGroupNames();
		if (!CollectionUtils.isEmpty(groupNames)) {
			for (Assertion assertion : source.getAssertionsInGroups(groupNames)) {
				if (already.add(assertion.getUuid())) {
					selected.add(assertion);
				}
			}
		}

		List<String> excluded = executionConfig.getAssertionExclusionList();
		if (!CollectionUtils.isEmpty(excluded)) {
			selected.removeIf(a -> excluded.contains(a.getUuid().toString()));
		}
		return selected;
	}

	private List<TestRunItem> executeAssertions(DuckDbAssertionExecutionService executionService,
			List<Assertion> assertions, String reportStorage) {
		reportService.writeProgress(START_EXECUTING_ASSERTIONS, reportStorage);
		List<TestRunItem> results = new ArrayList<>();
		for (int from = 0; from < assertions.size(); from += PROGRESS_EVERY) {
			int to = Math.min(from + PROGRESS_EVERY, assertions.size());
			results.addAll(executionService.execute(assertions.subList(from, to)));
			reportService.writeProgress(String.format("[%1s] of [%2s] assertions are completed.",
					to, assertions.size()), reportStorage);
		}
		return results;
	}

	/**
	 * {@code MysqlValidationService.constructTestReport}, with the DuckDB
	 * extractor in place of the MySQL one and its now-unused assertion list
	 * dropped. The classification is copied exactly, including the part that
	 * looks like a bug and is not: a {@code failureCount} of -1 lands in BOTH
	 * the incomplete list and the failed list. An assertion that did not run is
	 * not a pass, and RVF counts it as a failure as well as reporting it as
	 * incomplete.
	 */
	private void constructTestReport(ValidationStatusReport statusReport,
			MysqlExecutionConfig executionConfig, long timeStart, List<TestRunItem> items,
			DuckFailuresExtractor extractor) {
		ValidationReport report = statusReport.getResultReport();
		try {
			extractor.extractTestResults(items, executionConfig);

			final List<TestRunItem> failedItems = new ArrayList<>();
			final List<TestRunItem> warningItems = new ArrayList<>();
			final List<TestRunItem> incompleteItems = new ArrayList<>();
			for (final TestRunItem item : items) {
				item.setTestType(TestType.SQL);
				if (item.getFailureCount() != 0) {
					if (item.getFailureCount() == -1L) {
						incompleteItems.add(item);
					}
					if (SeverityLevel.WARN.toString().equalsIgnoreCase(item.getSeverity())) {
						warningItems.add(item);
					} else {
						failedItems.add(item);
					}
				}
			}
			report.addFailedAssertions(failedItems);
			report.addWarningAssertions(warningItems);
			report.addIncompleteAssesrtions(incompleteItems);
			items.removeAll(failedItems);
			items.removeAll(warningItems);
			report.addPassedAssertions(items);
		} catch (SQLException | RestClientException exception) {
			report.addFailedAssertions(Collections.emptyList());
			report.addWarningAssertions(Collections.emptyList());
			report.addPassedAssertions(Collections.emptyList());
			statusReport.addFailureMessage(
					ExceptionUtils.getExceptionCause("Failed to extract test results", exception));
		}
		report.addTimeTaken((System.currentTimeMillis() - timeStart) / 1000);
	}

	private DuckAssertionSource assertionSource(DuckStore store) {
		try (InputStream groups = Files.newInputStream(Path.of(corpusRoot, "groups.xml"));
				InputStream policies = Files.newInputStream(Path.of(corpusRoot, "policies.xml"))) {
			return DuckAssertionSource.from(store, groups, policies);
		} catch (IOException e) {
			// Fatal rather than an empty source: with no grouping files every
			// requested group resolves to nothing, and a run over zero
			// assertions reports a clean pass.
			throw new IllegalStateException(
					"Could not read groups.xml/policies.xml from assertion corpus " + corpusRoot, e);
		}
	}

	private void materialise(Connection connection, Path releaseDir, String schema, DuckStore store)
			throws SQLException, IOException {
		DuckMaterialiser.Result result =
				DuckMaterialiser.materialise(connection, releaseDir, schema, store.tableColumns());
		LOGGER.info("Materialised {} as schema '{}': {} tables, {} rows, {} empty files, {} placeholders",
				releaseDir, schema, result.tablesLoaded(), result.rows(), result.emptyFiles(),
				result.placeholders());
	}

	/**
	 * Session settings, neither of them optional, both established against the
	 * real corpus by DuckStoreProbe. They do not carry to another connection.
	 */
	private void session(Connection connection) throws SQLException {
		try (Statement st = connection.createStatement()) {
			// pre-requisites.sql refers to its inputs unqualified - "FROM
			// concept_s", not "FROM prospective.concept_s".
			st.execute("SET search_path='" + PROSPECTIVE_SCHEMA + "'");
			// MySQL casts freely between types and DuckDB does not; amtv4's
			// isValidComponentId_cr calls length() on a BIGINT column.
			st.execute("SET old_implicit_casting=true");
		}
	}

	private void createResultTable(Connection connection) throws SQLException {
		try (Statement st = connection.createStatement()) {
			st.execute("CREATE SCHEMA IF NOT EXISTS " + QA_RESULT_SCHEMA);
			// RVF's qa_result, including skip_module_check (upstream 051e87e) so
			// an assertion naming that column can insert into it.
			st.execute("CREATE TABLE IF NOT EXISTS " + qaResultTable + " ("
					+ "id BIGINT, run_id BIGINT, assertion_id VARCHAR, concept_id BIGINT, "
					+ "details VARCHAR, component_id VARCHAR, table_name VARCHAR, "
					+ "skip_module_check BOOLEAN)");
		}
	}

	private Path databaseFile(Long runId) {
		return Path.of(workDirectory, "rvf_duck_" + runId + ".duckdb");
	}

	/**
	 * DuckDB writes a database as up to three things - the file, a write-ahead
	 * log beside it and a spill directory - and only the first is named by the
	 * JDBC url. Deleting just that one leaves the release's rows on disk under a
	 * name nothing will ever come back for.
	 */
	private void deleteDatabase(Path database) {
		FileUtils.deleteQuietly(database.toFile());
		FileUtils.deleteQuietly(Path.of(database + ".wal").toFile());
		FileUtils.deleteQuietly(Path.of(database + ".tmp").toFile());
	}

	/**
	 * The value {@code <VERSION>} binds to, read from the release's own RF2
	 * filenames.
	 *
	 * <p>MySQL gets it from {@code prospectiveVersion.split("_")[2]}, and that
	 * schema name is itself built from the effectiveTime suffix of an sct2_/der2_
	 * file in the package - so this reads the same thing from the same place,
	 * one step earlier. Deliberately NOT
	 * {@code MysqlExecutionConfig.getEffectiveTime()}, which is the effectiveTime
	 * the caller DECLARED for the run and is frequently absent: an assertion
	 * comparing {@code effectivetime = '<VERSION>'} against a wrong value matches
	 * nothing, so "is there no row at this version" flags every row it looks at.
	 *
	 * <p>Sorted rather than first-found. MySQL takes whichever entry the zip
	 * happens to yield first, which is not reproducible between two readings of
	 * the same package.
	 */
	static String releaseVersion(Path releaseDir) throws IOException {
		try (Stream<Path> walk = Files.walk(releaseDir)) {
			return walk.filter(Files::isRegularFile)
					.map(p -> p.getFileName().toString())
					.filter(name -> (name.contains("sct2_") || name.contains("der2_"))
							&& name.endsWith(".txt"))
					.sorted()
					.map(RF2_VERSION::matcher)
					.filter(Matcher::matches)
					.map(m -> m.group(1))
					.findFirst()
					// DuckBinder turns a null into RVF's own NOT_SUPPLIED.
					.orElse(null);
		}
	}

	/** The RF2 files this release contributes, for the report's file list. */
	private List<String> rf2FileNames(Path releaseDir) throws IOException {
		return DuckMaterialiser.releaseFiles(releaseDir).values().stream()
				.flatMap(List::stream)
				.map(p -> p.getFileName().toString())
				.sorted()
				.toList();
	}
}
