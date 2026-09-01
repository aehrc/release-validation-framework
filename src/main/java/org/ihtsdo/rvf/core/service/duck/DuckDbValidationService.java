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
import org.ihtsdo.rvf.core.service.ReleaseAcquisitionService;
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
import org.ihtsdo.rvf.core.service.SqlAssertionValidationService;
import org.springframework.util.StringUtils;
import org.springframework.util.CollectionUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
public class DuckDbValidationService implements SqlAssertionValidationService {

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

	/**
	 * Where an extension is merged with its dependency. A fourth schema rather
	 * than a merge INTO {@code prospective}, which is what MySQL does: the
	 * release-type assertions have already run against the extension alone by
	 * then, but a run that had to be re-examined afterwards would find its
	 * inputs overwritten.
	 */
	private static final String COMBINED_SCHEMA = "combined";

	/** Marks which side of the extension/dependency merge a row came from. */
	private static final String MERGE_SOURCE = "rvf_merge_source";

	private static final String EFFECTIVE_TIME = "effectivetime";

	/** Assertions that compare a package against its own previous release. */
	private static final String RELEASE_TYPE_VALIDATION = "release-type-validation";

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
	private final ReleaseAcquisitionService acquisitionService;
	private final DuckStoreLocator storeLocator;
	private final String corpusRoot;
	private final String workDirectory;
	private final String qaResultTable;
	private final int duckThreads;
	private final String duckMemoryLimit;
	private final boolean archiveFailures;
	private final DuckReleaseCache releaseCache;

	public DuckDbValidationService(ValidationReportService reportService,
			WhitelistService whitelistService,
			ReleaseAcquisitionService acquisitionService,
			DuckStoreLocator storeLocator,
			@Value("${rvf.assertion.resource.local.path:}") String corpusRoot,
			@Value("${rvf.duck.work.directory:${java.io.tmpdir}}") String workDirectory,
			@Value("${rvf.qa.result.table.name:qa_result}") String qaResultTableName,
			@Value("${rvf.duck.threads:0}") int duckThreads,
			@Value("${rvf.duck.memory.limit:}") String duckMemoryLimit,
			@Value("${rvf.duck.archive.failures:true}") boolean archiveFailures,
			@Value("${rvf.duck.cache.directory:}") String cacheDirectory,
			@Value("${rvf.duck.cache.max-gb:0}") double cacheMaxGb) {
		this.reportService = reportService;
		this.whitelistService = whitelistService;
		this.acquisitionService = acquisitionService;
		this.storeLocator = storeLocator;
		this.corpusRoot = corpusRoot;
		this.workDirectory = workDirectory;
		this.qaResultTable = QA_RESULT_SCHEMA + "." + qaResultTableName;
		this.duckThreads = duckThreads;
		this.duckMemoryLimit = duckMemoryLimit;
		this.archiveFailures = archiveFailures;
		// Default off. A cache that appeared without being asked for would change
		// the disk footprint of an existing deployment by gigabytes.
		Path cacheDir = Path.of(cacheDirectory == null || cacheDirectory.isBlank()
				? workDirectory + "/release-cache" : cacheDirectory);
		this.releaseCache = new DuckReleaseCache(cacheDir, (long) (cacheMaxGb * 1073741824L));
		if (this.releaseCache.isEnabled()) {
			LOGGER.info("Release cache enabled: {} with a {} GB budget", cacheDir, cacheMaxGb);
		}
	}

	/**
	 * Bound DuckDB to the CPU and memory this process was actually given.
	 *
	 * <p>DuckDB sizes its thread pool from the machine, and it does NOT observe
	 * the restrictions a container or a scheduler imposes. Measured on this
	 * codebase: under {@code taskset -c 0-1}, {@code nproc} reports 2 and the
	 * JVM's {@code availableProcessors()} reports 2, while DuckDB still reports
	 * {@code threads=10} - the host's core count.
	 *
	 * <p>Why that matters in production rather than in theory: a Kubernetes pod
	 * with {@code limits.cpu: 2} on a 64-core node gets a CFS quota, not two
	 * cores. DuckDB would start 64 worker threads inside a two-core budget, and
	 * every one of them contends for the same quota - so the pod is throttled
	 * hard and runs SLOWER than it would with two threads. The same applies to
	 * an HPC allocation of a few cores on a many-core node.
	 *
	 * <p>{@code availableProcessors()} is the right source because the JVM does
	 * observe both affinity and cgroup quotas, so it reports what this process
	 * may actually use. {@code rvf.duck.threads} overrides it for benchmarking
	 * or for deliberately oversubscribing; {@code rvf.duck.memory.limit} is left
	 * unset by default, which keeps DuckDB's own heuristic.
	 */
	private void applyResourceLimits(Connection connection) throws SQLException {
		int threads = duckThreads > 0 ? duckThreads : Runtime.getRuntime().availableProcessors();
		try (Statement statement = connection.createStatement()) {
			statement.execute("SET threads = " + threads);
			if (duckMemoryLimit != null && !duckMemoryLimit.isBlank()) {
				statement.execute("SET memory_limit = '" + duckMemoryLimit.replace("'", "") + "'");
			}
		}
		LOGGER.info("DuckDB resource limits: threads={}{}", threads,
				duckMemoryLimit == null || duckMemoryLimit.isBlank()
						? "" : ", memory_limit=" + duckMemoryLimit);
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
		// The injected bean, not `new`. This used to construct a
		// ValidationVersionLoader by hand to reuse createExecutionConfig, with a
		// comment predicting that it would NPE the day that method touched an
		// injected field - which is exactly what happened when the acquisition
		// half was extracted. ReleaseAcquisitionService carries no engine
		// condition, so there is a bean to take and the hazard is gone rather
		// than moved.
		MysqlExecutionConfig executionConfig =
				acquisitionService.createExecutionConfig(validationConfig);
		return runValidations(executionConfig, releases, validationConfig.getStorageLocation(),
				statusReport);
	}

	/**
	 * The {@link SqlAssertionValidationService} entry point: takes a run config,
	 * finds the release files acquisition left on disk, unpacks them and
	 * validates.
	 *
	 * <p>Acquisition hands over ZIPs in one flat, unlabelled list -
	 * {@code validationConfig.getLocalReleaseFiles()} holds the prospective, the
	 * previous and every dependency together, in whatever order they were
	 * downloaded. They are told apart by NAME, against
	 * {@code getPreviousRelease()} and {@code getExtensionDependencies()}, and
	 * not by position: the download order depends on which branches
	 * ValidationRunner took, so an index would be right until the day a run
	 * supplies no previous release and every subsequent file shifts up one.
	 *
	 * <p>A release that was requested and is NOT on disk is left null rather than
	 * pointed at an empty directory - see {@link ReleaseDirectories}, where null
	 * is what turns an assertion over a release we do not have into "not run"
	 * instead of a pass.
	 */
	@Override
	public ValidationStatusReport runRF2Validations(ValidationRunConfig validationConfig,
			ValidationStatusReport statusReport) {
		MysqlExecutionConfig executionConfig =
				acquisitionService.createExecutionConfig(validationConfig);
		Path work = Path.of(workDirectory, "rvf-" + validationConfig.getRunId());
		try {
			ReleaseDirectories releases = unpack(validationConfig, executionConfig, work);
			return runValidations(executionConfig, releases, validationConfig.getStorageLocation(),
					statusReport);
		} catch (IOException e) {
			String message = ExceptionUtils.getExceptionCause(
					"Failed to unpack the release files for validation", e);
			LOGGER.error(message, e);
			statusReport.addFailureMessage(message);
			statusReport.getReportSummary().put(SQL_SUMMARY_KEY, message);
			return statusReport;
		} finally {
			FileUtils.deleteQuietly(work.toFile());
		}
	}

	private ReleaseDirectories unpack(ValidationRunConfig validationConfig,
			MysqlExecutionConfig executionConfig, Path work) throws IOException {
		Files.createDirectories(work);
		Collection<String> excluded = executionConfig.getExcludedRF2Files();

		File prospectiveZip = validationConfig.getLocalProspectiveFile();
		if (prospectiveZip == null) {
			throw new IOException("No prospective release file was acquired for this run");
		}
		Path prospective = DuckReleaseUnpacker.unpack(prospectiveZip, work, "prospective", excluded);

		Path previous = null;
		File previousZip = localFileNamed(validationConfig, validationConfig.getPreviousRelease());
		if (previousZip != null) {
			previous = DuckReleaseUnpacker.unpack(previousZip, work, "previous", excluded);
		}

		Path dependency = null;
		List<String> dependencies = validationConfig.getExtensionDependencies();
		if (!CollectionUtils.isEmpty(dependencies)) {
			// One directory for all of them, which is what an extension with
			// several dependencies means: the union is the content the extension
			// sits on top of, and DuckMaterialiser already loads every file that
			// maps to a table as one relation.
			for (String name : dependencies) {
				File zip = localFileNamed(validationConfig, name);
				if (zip != null) {
					dependency = DuckReleaseUnpacker.unpack(zip, work, "dependency", excluded);
				}
			}
		}
		return new ReleaseDirectories(prospective, previous, dependency);
	}

	/**
	 * The acquired file whose name matches, or null if that release was not
	 * requested or did not arrive.
	 */
	private static File localFileNamed(ValidationRunConfig validationConfig, String name) {
		if (!StringUtils.hasLength(name)) {
			return null;
		}
		List<File> acquired = validationConfig.getLocalReleaseFiles();
		if (acquired == null) {
			return null;
		}
		for (File file : acquired) {
			if (name.equals(file.getName())) {
				return file;
			}
		}
		return null;
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
		// ValidationRunner hands both engines a status report that already
		// carries an empty ValidationReport, and constructTestReport fills that
		// one in rather than replacing it - the MySQL service assumes the same.
		// Guarded rather than assumed because the failure otherwise lands as an
		// NPE inside the catch block below, from a class that documents itself
		// as never throwing. The status report's OWN collections cannot be
		// guarded from here: it has no setter for them, so a caller must still
		// build it with the ValidationRunConfig constructor rather than the
		// no-arg one Jackson uses.
		if (statusReport.getResultReport() == null) {
			ValidationReport report = new ValidationReport();
			report.setExecutionId(executionConfig.getExecutionId());
			statusReport.setResultReport(report);
		}
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
				applyResourceLimits(connection);
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
		DuckStore store = storeLocator.load();
		LOGGER.info("DuckDB assertion store loaded from {}: {}", storeLocator.description(),
				store.generatorDescription());

		String lastItemLoadAttempted = "Item Unknown";
		try {
			lastItemLoadAttempted = "Prospective Release - " + releases.prospective();
			materialise(connection, releases.prospective(), PROSPECTIVE_SCHEMA, store);
			// Only now: pre-requisites.sql names its inputs unqualified, so the
			// search path has to point at a schema that already exists.
			session(connection, PROSPECTIVE_SCHEMA);
			statusReport.setRF2Files(rf2FileNames(releases.prospective()));

			if (releases.previous() != null) {
				lastItemLoadAttempted = "Previous Release - " + releases.previous();
				materialise(connection, releases.previous(), PREVIOUS_SCHEMA, store);
			}
			if (executionConfig.isRf2DeltaOnly()) {
				lastItemLoadAttempted = "Delta-only rebuild of the prospective snapshot";
				rebuildSnapshotFromPreviousAndDelta(connection, store, releases);
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

		DuckAssertionSource source = assertionSource(store);
		Selection selection = selectAssertions(source, executionConfig);
		LOGGER.info("Total assertions to run {} for groups {}", selection.total(),
				executionConfig.getGroupNames());

		// Derived once, not per phase: it walks the release directory, and both
		// phases must bind the SAME <VERSION> or their findings disagree about
		// which release they describe.
		String version = releaseVersion(releases.prospective());

		List<TestRunItem> items;
		try {
			items = executionConfig.isExtensionValidation() && !executionConfig.isReleaseAsAnEdition()
					? runExtensionSplit(connection, store, executionConfig, releases,
							reportStorage, selection, version)
					: runSinglePhase(connection, store, executionConfig, releases,
							reportStorage, selection, version);
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
		} catch (SQLException e) {
			// Reached only from the extension combine. See runExtensionSplit for
			// why this aborts the run instead of reporting alongside it.
			String message = ExceptionUtils.getExceptionCause(
					"Failed to combine the extension with its dependency", e);
			LOGGER.error(message, e);
			statusReport.addFailureMessage(message);
			statusReport.getReportSummary().put(SQL_SUMMARY_KEY, message);
			return statusReport;
		}

		constructTestReport(statusReport, executionConfig, timeStart, items,
				new DuckFailuresExtractor(connection, qaResultTable, whitelistService,
						source::findAll));
		archiveFailures(connection, reportStorage);
		return statusReport;
	}

	/**
	 * Every assertion against one schema - an international or edition release,
	 * and the shape production runs.
	 */
	private List<TestRunItem> runSinglePhase(Connection connection, DuckStore store,
			MysqlExecutionConfig executionConfig, ReleaseDirectories releases,
			String reportStorage, Selection selection, String version) {
		DuckDbAssertionExecutionService executionService = executionService(
				connection, store, executionConfig, releases, PROSPECTIVE_SCHEMA, version);
		executionService.prepareSchema();
		List<TestRunItem> items = new ArrayList<>(
				executeAssertions(executionService, selection.resources(), reportStorage));
		items.addAll(executeAssertions(executionService, selection.assertions(), reportStorage));
		return items;
	}

	/**
	 * The extension shape: release-type assertions against the extension alone,
	 * everything else against the extension merged with its dependency.
	 *
	 * <p>Mirrors {@code MysqlValidationService.runExtensionReleaseValidation}.
	 * The split is not cosmetic in either engine. A release-type assertion asks
	 * "did this package change the right way since its previous release", so it
	 * must see the extension's OWN delta/snapshot/full - merge the dependency in
	 * first and every international component reads as newly added. Every other
	 * assertion asks a question about a coherent terminology, so it must see the
	 * dependency too - unmerged, every AU description of an international concept
	 * is an orphan and every AU relationship points at a concept that does not
	 * exist.
	 *
	 * <p>An earlier version of this class did not reproduce the split, on the
	 * reasoning that {@code <DEPENDENCY>} already names the dependency schema so
	 * nothing needs merging. That was wrong: only a handful of assertions mention
	 * {@code <DEPENDENCY>} explicitly, and the rest address {@code curr_*} alone.
	 *
	 * <p>Unlike the MySQL service, a failed combine ABORTS. MySQL catches the
	 * exception, reports it as a message, and then runs the remaining assertions
	 * anyway - against a schema whose combine stopped part way. Those assertions
	 * find nothing and report clean, so the run's failure count is unaffected by
	 * a merge that did not happen. See UPSTREAM-SQL-DEFECTS.md, defect 7.
	 */
	private List<TestRunItem> runExtensionSplit(Connection connection, DuckStore store,
			MysqlExecutionConfig executionConfig, ReleaseDirectories releases,
			String reportStorage, Selection selection, String version) throws SQLException {
		List<Assertion> releaseType = new ArrayList<>();
		List<Assertion> rest = new ArrayList<>();
		for (Assertion assertion : selection.assertions()) {
			String keywords = assertion.getKeywords();
			if (keywords != null && keywords.contains(RELEASE_TYPE_VALIDATION)) {
				releaseType.add(assertion);
			} else {
				rest.add(assertion);
			}
		}
		LOGGER.info("Extension validation: {} release-type assertions against the extension, "
				+ "{} against the extension combined with its dependency",
				releaseType.size(), rest.size());

		DuckDbAssertionExecutionService extensionPhase = executionService(
				connection, store, executionConfig, releases, PROSPECTIVE_SCHEMA, version);
		extensionPhase.prepareSchema();
		// Resource assertions first, in THIS schema. They are re-run below in the
		// combined one, exactly as MysqlValidationService re-runs them by calling
		// runAssertionTests once per phase: the tables they build live in the
		// schema the phase addresses, so a set built in `prospective` is not
		// there to be read when the next phase addresses `combined`.
		List<TestRunItem> items = new ArrayList<>(
				executeAssertions(extensionPhase, selection.resources(), reportStorage));
		items.addAll(executeAssertions(extensionPhase, releaseType, reportStorage));

		if (executionConfig.isStandAloneProduct()) {
			// A stand-alone product has no dependency to merge; MySQL skips the
			// combine here too and runs the rest against the extension schema.
			LOGGER.info("Stand-alone product - no dependency combine");
			items.addAll(executeAssertions(extensionPhase, rest, reportStorage));
			return items;
		}
		if (releases.dependency() == null) {
			throw new SQLException("Extension validation needs a dependency release, none supplied");
		}

		combineExtensionWithDependency(connection, store);
		DuckDbAssertionExecutionService combinedPhase = executionService(
				connection, store, executionConfig, releases, COMBINED_SCHEMA, version);
		// The pre-requisite and port tables are built by unqualified DDL, so
		// they landed in `prospective`. The combined schema needs its OWN -
		// a transitive closure over the extension alone would leave every
		// international ancestor out of it.
		combinedPhase.prepareSchema();
		items.addAll(executeAssertions(combinedPhase, selection.resources(), reportStorage));
		items.addAll(executeAssertions(combinedPhase, rest, reportStorage));
		return items;
	}

	/**
	 * Delta-only validation: the prospective package ships a delta and nothing
	 * else, so the snapshot it would be validated against has to be built -
	 * previous release's snapshot, with this delta applied over it.
	 *
	 * <p>Mirrors {@code ValidationVersionLoader
	 * .loadProspectiveDeltaAndCombineWithPreviousSnapshotIntoDB} and the
	 * {@code ReleaseDataManager.updateSnapshotTableWithDataFromDelta} it calls:
	 * copy the previous snapshot in, delete every row the delta supersedes, then
	 * insert the delta. A component's delta row wins whether it is an addition,
	 * a change or an inactivation, which is what makes delete-then-insert right
	 * rather than an upsert.
	 *
	 * <p>With no previous release the snapshot stays empty, as on MySQL - the
	 * delta alone IS the release for a first-time load.
	 *
	 * <p>Keyed on {@link #keyColumns} rather than on {@code id}. MySQL hardcodes
	 * {@code id} here too, so on {@code identifier_s} its DELETE raises Unknown
	 * column - and unlike the extension combine that failure is caught and
	 * logged per table, so the identifier snapshot silently keeps the previous
	 * release's rows and never sees this delta. Third instance of the same root
	 * cause; see UPSTREAM-SQL-DEFECTS.md defects 1 and 7.
	 */
	private void rebuildSnapshotFromPreviousAndDelta(Connection connection, DuckStore store,
			ReleaseDirectories releases) throws SQLException {
		long t0 = System.currentTimeMillis();
		if (releases.previous() == null) {
			LOGGER.info("Delta-only run with no previous release - the prospective snapshot "
					+ "stays as the delta alone");
			return;
		}
		Map<String, String> tableColumns = store.tableColumns();
		int rebuilt = 0;
		try (Statement st = connection.createStatement()) {
			for (Map.Entry<String, String> entry : new TreeMap<>(tableColumns).entrySet()) {
				String snapshot = entry.getKey();
				if (!snapshot.endsWith("_s")) {
					continue;
				}
				String delta = snapshot.substring(0, snapshot.length() - 2) + "_d";
				if (!tableColumns.containsKey(delta)) {
					continue;
				}
				List<String> key = keyColumns(snapshot, columnNames(entry.getValue()));
				String join = key.stream()
						.map(c -> "s." + c + " = d." + c)
						.collect(Collectors.joining(" AND "));
				st.execute("INSERT INTO " + PROSPECTIVE_SCHEMA + "." + snapshot
						+ " SELECT * FROM " + PREVIOUS_SCHEMA + "." + snapshot);
				st.execute("DELETE FROM " + PROSPECTIVE_SCHEMA + "." + snapshot + " s WHERE EXISTS "
						+ "(SELECT 1 FROM " + PROSPECTIVE_SCHEMA + "." + delta + " d WHERE " + join + ")");
				st.execute("INSERT INTO " + PROSPECTIVE_SCHEMA + "." + snapshot
						+ " SELECT * FROM " + PROSPECTIVE_SCHEMA + "." + delta);
				rebuilt++;
			}
		}
		LOGGER.info("Delta-only: rebuilt {} snapshot tables from the previous release and this "
				+ "delta, in {}ms", rebuilt, System.currentTimeMillis() - t0);
	}

	/**
	 * Builds {@link #COMBINED_SCHEMA}: the extension's own delta and full, and a
	 * snapshot merged with the dependency's.
	 *
	 * <p>The merge rule is MySQL's, restated. {@code ReleaseDataManager.copyData}
	 * runs four inserts per table - dependency rows with no counterpart, extension
	 * rows with no counterpart, dependency rows that tie or beat their
	 * counterpart's effectiveTime ({@code >=}), and extension rows that strictly
	 * beat theirs ({@code >}). Those four are mutually exclusive and exhaustive,
	 * so together they mean: one row per key, highest effectiveTime, dependency
	 * wins a tie. That is what the window function below says in one statement.
	 *
	 * <p>Two deliberate deviations from the MySQL text:
	 * <ul>
	 * <li>effectiveTime is compared AS STORED rather than through
	 *     {@code cast(... as datetime)}. The column is {@code char(8)} holding
	 *     zero-padded {@code YYYYMMDD}, whose lexicographic order is its
	 *     chronological order, so the cast changes no answer - and DuckDB will
	 *     not perform it.
	 * <li>the partition key is the table's ACTUAL key. MySQL hardcodes
	 *     {@code id}, which {@code identifier_s} does not have - see
	 *     {@link #keyColumns}.
	 * </ul>
	 */
	private void combineExtensionWithDependency(Connection connection, DuckStore store)
			throws SQLException {
		long t0 = System.currentTimeMillis();
		Map<String, String> tableColumns = store.tableColumns();
		int merged = 0;
		int copied = 0;
		try (Statement st = connection.createStatement()) {
			st.execute("CREATE SCHEMA IF NOT EXISTS " + COMBINED_SCHEMA);
			for (Map.Entry<String, String> entry : new TreeMap<>(tableColumns).entrySet()) {
				String table = entry.getKey();
				List<String> columns = columnNames(entry.getValue());
				if (table.endsWith("_s")) {
					st.execute(mergedSnapshotSql(table, columns));
					merged++;
				} else {
					// Delta and full stay the extension's own. The dependency's
					// history is not this package's to restate.
					st.execute("CREATE OR REPLACE TABLE " + COMBINED_SCHEMA + "." + table
							+ " AS SELECT * FROM " + PROSPECTIVE_SCHEMA + "." + table);
					copied++;
				}
			}
		}
		LOGGER.info("Combined extension with dependency: {} snapshot tables merged, "
				+ "{} delta/full tables copied, in {}ms",
				merged, copied, System.currentTimeMillis() - t0);
	}

	private String mergedSnapshotSql(String table, List<String> columns) {
		String select = String.join(", ", columns);
		String key = String.join(", ", keyColumns(table, columns));
		String order = columns.contains(EFFECTIVE_TIME)
				? EFFECTIVE_TIME + " DESC, " + MERGE_SOURCE
				: MERGE_SOURCE;
		return "CREATE OR REPLACE TABLE " + COMBINED_SCHEMA + "." + table + " AS SELECT " + select
				+ " FROM (SELECT *, 0 AS " + MERGE_SOURCE + " FROM " + DEPENDENCY_SCHEMA + "." + table
				+ " UNION ALL SELECT *, 1 AS " + MERGE_SOURCE + " FROM " + PROSPECTIVE_SCHEMA + "." + table
				+ ") QUALIFY row_number() OVER (PARTITION BY " + key + " ORDER BY " + order + ") = 1";
	}

	/**
	 * The columns that identify one component in a snapshot table.
	 *
	 * <p>{@code id} for all but one. {@code identifier_s} carries the RF2
	 * Identifier file, which is keyed on
	 * {@code (identifierSchemeId, alternateIdentifier)} and has no {@code id}
	 * column at all - and MySQL's merge names {@code id} unconditionally, so on
	 * that one table it raises "Unknown column" and takes the rest of the combine
	 * down with it.
	 */
	private static List<String> keyColumns(String table, List<String> columns) {
		if (columns.contains("id")) {
			return List.of("id");
		}
		if (columns.contains("identifierschemeid") && columns.contains("alternateidentifier")) {
			return List.of("identifierschemeid", "alternateidentifier");
		}
		throw new IllegalStateException(
				"No key columns known for " + table + " - cannot merge it with a dependency");
	}

	/** Column names out of the DDL fragment the store carries per table. */
	private static List<String> columnNames(String columnSpec) {
		List<String> names = new ArrayList<>();
		for (String column : columnSpec.split(",")) {
			String trimmed = column.trim();
			if (trimmed.isEmpty()) {
				continue;
			}
			int space = trimmed.indexOf(' ');
			names.add((space < 0 ? trimmed : trimmed.substring(0, space)).toLowerCase(Locale.ROOT));
		}
		return names;
	}

	private DuckDbAssertionExecutionService executionService(Connection connection, DuckStore store,
			MysqlExecutionConfig executionConfig, ReleaseDirectories releases, String schema,
			String version) {
		DuckBinder binder = new DuckBinder(store.sentinels(), new DuckBinder.Config(
				executionConfig.getExecutionId(), schema,
				releases.previous() == null ? null : PREVIOUS_SCHEMA,
				releases.dependency() == null ? null : DEPENDENCY_SCHEMA,
				qaResultTable, executionConfig.getDefaultModuleId(),
				executionConfig.getIncludedModules(), version));
		try {
			session(connection, schema);
		} catch (SQLException e) {
			// Same class of failure as a setup statement failing, and treated
			// alike: without the search path the pre-requisites build in, or
			// read from, the wrong schema.
			throw new DuckDbAssertionExecutionService.SetupFailedException(
					List.of("SET search_path='" + schema + "' -> " + e.getMessage()));
		}
		return new DuckDbAssertionExecutionService(store, binder, connection);
	}

	/**
	 * The resource assertions, and the requested groups' assertions, kept APART.
	 *
	 * <p>They have to stay apart because the resource set is not just "more
	 * assertions to run first" - it is infrastructure that builds the shared
	 * intermediate tables (res_edited_active_concepts, tmp_pt, ancestors) other
	 * assertions select from, and it has to be rebuilt in every SCHEMA a phase
	 * runs against. Merged into one ordered list, as an earlier version of this
	 * did, the extension split then partitions that list by keyword and every
	 * resource assertion lands in the non-release-type half - so the
	 * release-type phase runs against a schema where none of those tables were
	 * ever built. MySQL avoids this by re-fetching the resource set inside
	 * {@code runAssertionTests}, which both of its phases call.
	 *
	 * <p>{@code assertions} excludes the resource set, which MySQL does not do.
	 * It gets away with that because no resource assertion is in a requested
	 * group today; were one ever added, MySQL would run it twice within a single
	 * phase - and a second run inserts a second copy of every finding, so the
	 * assertion's reported failure count doubles.
	 */
	private record Selection(List<Assertion> resources, List<Assertion> assertions) {

		int total() {
			return resources.size() + assertions.size();
		}
	}

	private Selection selectAssertions(DuckAssertionSource source,
			MysqlExecutionConfig executionConfig) {
		List<Assertion> resourceAssertions = source.getAssertionsByKeyWords(RESOURCE_KEYWORD, true);
		LOGGER.info("Found total resource assertions need to be run before test {}",
				resourceAssertions.size());

		Set<UUID> already = new LinkedHashSet<>();
		resourceAssertions.forEach(a -> already.add(a.getUuid()));
		List<Assertion> selected = new ArrayList<>();
		List<String> groupNames = executionConfig.getGroupNames();
		if (!CollectionUtils.isEmpty(groupNames)) {
			for (Assertion assertion : source.getAssertionsInGroups(groupNames)) {
				if (already.add(assertion.getUuid())) {
					selected.add(assertion);
				}
			}
		}

		List<String> excluded = executionConfig.getAssertionExclusionList();
		List<Assertion> resources = new ArrayList<>(resourceAssertions);
		if (!CollectionUtils.isEmpty(excluded)) {
			selected.removeIf(a -> excluded.contains(a.getUuid().toString()));
			resources.removeIf(a -> excluded.contains(a.getUuid().toString()));
		}
		return new Selection(resources, selected);
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

	/**
	 * Puts a release into the run database, from the cache where that is allowed.
	 *
	 * <p>Only {@code previous} and {@code dependency} are cacheable, and the
	 * restriction is not conservatism: they are the only schemas nothing writes
	 * to. Every assertion phase runs against {@code prospective} or
	 * {@code combined} - see the resource-assertion note on {@link Selection} for
	 * why the intermediate tables are rebuilt per phase schema - so a read-only
	 * attachment of either of those would fail on the first resource assertion.
	 * The prospective release is also different every run, so there would be
	 * nothing to hit.
	 */
	private void materialise(Connection connection, Path releaseDir, String schema, DuckStore store)
			throws SQLException, IOException {
		if (releaseCache != null && releaseCache.isEnabled() && isCacheable(schema)) {
			Path cached = releaseCache.get(releaseDir, store.tableColumns());
			if (cached != null) {
				DuckReleaseCache.attach(connection, cached, schema);
				LOGGER.info("Attached cached release {} as schema '{}'", cached.getFileName(), schema);
				return;
			}
		}
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
	private void session(Connection connection, String schema) throws SQLException {
		try (Statement st = connection.createStatement()) {
			// pre-requisites.sql refers to its inputs unqualified - "FROM
			// concept_s", not "FROM prospective.concept_s". Which schema that
			// resolves to is the whole reason the extension split works: the
			// combined phase re-runs the same statements with the search path
			// moved, and they build over the merged tables instead.
			st.execute("SET search_path='" + schema + "'");
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

	/**
	 * Writes the run's FULL failure detail beside its report, before the run
	 * database - and every row in it - is deleted.
	 *
	 * <p>Why this is not simply a larger {@code results.json}: that file keeps
	 * {@code failureCount} plus the first {@code failureExportMax} instances, 10
	 * by default, and it is parsed in the browser by Release-Dashboard-UI. So the
	 * counts are durable and the rows behind them are not - "40,000 concepts
	 * failed" survives a run and "which 40,000" does not. Note this is not a
	 * DuckDB regression: MySQL loses the same rows, because its {@code qa_result}
	 * lives in {@code rvf_master} and {@code ddl-auto=create} drops it on the next
	 * boot.
	 *
	 * <p>Parquet+zstd because it is cheap enough not to need a policy decision -
	 * measured at 20.8MB per million rows against 177.3MB as CSV - and because
	 * DuckDB reads it back directly with {@code read_parquet()} without loading it
	 * into anything.
	 *
	 * <p>Best-effort by design. A validation that ran and reported is not failed
	 * by an archive that could not be written; the report is the deliverable and
	 * this is a convenience beside it.
	 */
	private void archiveFailures(Connection connection, String reportStorage) {
		if (!archiveFailures) {
			return;
		}
		Path parquet = Path.of(workDirectory, "rvf_failures_" + System.nanoTime() + ".parquet");
		try {
			try (Statement st = connection.createStatement()) {
				// COPY writes to the server's filesystem, which for an embedded
				// engine is ours, so this lands locally and is then handed to the
				// job store - the same route the structure report takes.
				st.execute("COPY (SELECT * FROM " + qaResultTable + ") TO '" + parquet
						+ "' (FORMAT PARQUET, COMPRESSION ZSTD)");
			}
			reportService.writeFailureArchive(reportStorage, parquet.toFile());
			LOGGER.info("Archived failure detail to {}failures.parquet ({} KB)", reportStorage,
					Files.size(parquet) / 1024);
		} catch (Exception e) {
			LOGGER.warn("Could not archive failure detail for {}: {}", reportStorage, e.toString());
		} finally {
			FileUtils.deleteQuietly(parquet.toFile());
		}
	}

	/** See {@link #materialise}: only the schemas nothing writes to. */
	private static boolean isCacheable(String schema) {
		return PREVIOUS_SCHEMA.equals(schema) || DEPENDENCY_SCHEMA.equals(schema);
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
