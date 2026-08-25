package org.ihtsdo.rvf;

import org.ihtsdo.rvf.core.data.model.TestRunItem;
import org.ihtsdo.rvf.core.data.model.ValidationReport;
import org.ihtsdo.rvf.core.service.ReleaseAcquisitionService;
import org.ihtsdo.rvf.core.service.ValidationReportService;
import org.ihtsdo.rvf.core.service.WhitelistService;
import org.ihtsdo.rvf.core.service.config.MysqlExecutionConfig;
import org.ihtsdo.rvf.core.service.config.ValidationRunConfig;
import org.ihtsdo.rvf.core.service.duck.DuckDbValidationService;
import org.ihtsdo.rvf.core.service.pojo.ValidationStatusReport;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import static org.mockito.Mockito.mock;

/**
 * Runs one validation through the REAL {@link DuckDbValidationService} and
 * prints what it reported.
 *
 * <p>Distinct from {@link DuckStoreProbe}, which drives
 * {@code DuckDbAssertionExecutionService} directly. That is the right level for
 * "does every statement in the corpus run", and the wrong one for "does a
 * validation produce the right report": everything the service adds - the
 * extension split, the dependency combine, schema selection, the failure
 * extractor, the report construction - sits above the execution service and is
 * exactly where a mode differs from a mode. A probe that reimplemented any of it
 * would be testing the reimplementation.
 *
 * <p>Usage:
 * <pre>
 *   -Dprobe.store=...          precompiled store.json                (required)
 *   -Dprobe.prospective=...    release directory                      (required)
 *   -Dprobe.previous=...       previous release directory
 *   -Dprobe.dependency=...     dependency release directory
 *   -Dprobe.extension=true     releaseAsAnEdition=false, split + combine
 *   -Dprobe.edition=true       releaseAsAnEdition=true
 *   -Dprobe.standalone=true    stand-alone product, no combine
 *   -Dprobe.deltaonly=true     package is a delta; rebuild the snapshot from it
 *   -Dprobe.modules=a,b,c      &lt;INCLUDED_MODULES&gt;
 *   -Dprobe.moduleid=x         &lt;MODULEID&gt;
 *   -Dprobe.groups=a,b         assertion groups (default: production's nine)
 *   -Dprobe.corpus=...         assertion resource root, for group resolution
 *   -Dprobe.out=...            TSV of every assertion and its failure count
 * </pre>
 */
public final class DuckValidationProbe {

	/** The nine groups production's daily-rvf posts. */
	private static final String DEFAULT_GROUPS = String.join(",", List.of(
			"file-centric-validation", "component-centric-validation",
			"release-type-validation", "released-content-validation",
			"mdrs", "common-mdrs-attribute-validation",
			"mdrs-source-effective-time-validation", "resource", "snapshot-content-validation"));

	private DuckValidationProbe() {
	}

	public static void main(String[] args) throws Exception {
		String store = required("probe.store");
		Path prospective = Path.of(required("probe.prospective"));
		Path previous = optionalPath("probe.previous");
		Path dependency = optionalPath("probe.dependency");

		MysqlExecutionConfig config = new MysqlExecutionConfig(System.currentTimeMillis() % 1_000_000_000L);
		config.setGroupNames(csv(System.getProperty("probe.groups", DEFAULT_GROUPS)));
		config.setExtensionValidation(Boolean.getBoolean("probe.extension"));
		config.setReleaseAsAnEdition(Boolean.getBoolean("probe.edition"));
		config.setStandAloneProduct(Boolean.getBoolean("probe.standalone"));
		config.setRf2DeltaOnly(Boolean.getBoolean("probe.deltaonly"));
		config.setIncludedModules(csv(System.getProperty("probe.modules", "")));
		config.setDefaultModuleId(System.getProperty("probe.moduleid"));
		config.setFailureExportMax(10);

		System.out.printf(Locale.ROOT,
				"mode: extension=%s edition=%s standalone=%s%n"
				+ "  prospective %s%n  previous    %s%n  dependency  %s%n  modules     %s%n",
				Boolean.getBoolean("probe.extension"), Boolean.getBoolean("probe.edition"),
				Boolean.getBoolean("probe.standalone"), prospective, previous, dependency,
				config.getIncludedModules());

		DuckDbValidationService service = new DuckDbValidationService(
				mock(ValidationReportService.class), mock(WhitelistService.class),
				// Real, not mocked: the probe calls runValidations directly, and
				// the only thing it uses this for is createExecutionConfig, which
				// is pure translation between two config objects.
				new ReleaseAcquisitionService(), store,
				System.getProperty("probe.corpus", ""),
				System.getProperty("probe.work", System.getProperty("java.io.tmpdir")),
				"qa_result");

		long t0 = System.currentTimeMillis();
		ValidationStatusReport status = service.runValidations(config,
				new DuckDbValidationService.ReleaseDirectories(prospective, previous, dependency),
				"probe/", statusReport(config));
		long elapsed = System.currentTimeMillis() - t0;

		report(status, elapsed);
		if (!status.getFailureMessages().isEmpty()) {
			// The service reports a broken run rather than throwing, so a probe
			// that only printed the numbers would exit 0 on a run that never
			// validated anything.
			System.err.println("FAILURE MESSAGES: " + status.getFailureMessages());
			System.exit(70);
		}
	}

	/**
	 * Built the way {@code ValidationRunner} builds it, not with the no-arg
	 * constructor: that one is Jackson's, and it leaves failureMessages,
	 * reportSummary and resultReport null, so the first thing the service tries
	 * to record on it fails.
	 */
	private static ValidationStatusReport statusReport(MysqlExecutionConfig config) {
		ValidationRunConfig runConfig = new ValidationRunConfig();
		runConfig.setRunId(config.getExecutionId());
		runConfig.setGroupsList(config.getGroupNames());
		ValidationStatusReport status = new ValidationStatusReport(runConfig);
		ValidationReport report = new ValidationReport();
		report.setExecutionId(config.getExecutionId());
		status.setResultReport(report);
		return status;
	}

	private static void report(ValidationStatusReport status, long elapsed) throws Exception {
		ValidationReport report = status.getResultReport();
		if (report == null) {
			System.out.printf(Locale.ROOT, "no report produced after %.1fs%n", elapsed / 1000.0);
			return;
		}
		System.out.printf(Locale.ROOT,
				"%n=== %.1fs wall clock ===%n"
				+ "  tests run  %d%n  failures   %d%n  warnings   %d%n"
				+ "  skipped    %d%n  incomplete %d%n  RF2 files  %d%n",
				elapsed / 1000.0, report.getTotalTestsRun(), report.getTotalFailures(),
				report.getTotalWarnings(), report.getTotalSkips(),
				report.getTotalTestsIncomplete(), status.getTotalRF2FilesLoaded());

		List<TestRunItem> all = new ArrayList<>();
		for (List<TestRunItem> bucket : Arrays.asList(report.getAssertionsFailed(),
				report.getAssertionsWarning(), report.getAssertionsPassed(),
				report.getAssertionsSkipped())) {
			if (bucket != null) {
				all.addAll(bucket);
			}
		}
		all.sort(Comparator.comparingLong(i -> -nullSafe(i.getFailureCount())));
		System.out.println("  top failing assertions:");
		all.stream().filter(i -> nullSafe(i.getFailureCount()) > 0).limit(15)
				.forEach(i -> System.out.printf(Locale.ROOT, "    %8d  %s%n",
						nullSafe(i.getFailureCount()), i.getAssertionText()));

		String out = System.getProperty("probe.out");
		if (out != null) {
			try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(Path.of(out)))) {
				w.println("uuid\tfailureCount\tseverity\tassertionText");
				for (TestRunItem i : all) {
					w.printf(Locale.ROOT, "%s\t%d\t%s\t%s%n", i.getAssertionUuid(),
							nullSafe(i.getFailureCount()), i.getSeverity(), i.getAssertionText());
				}
			}
			System.out.println("  wrote " + all.size() + " rows to " + out);
		}
	}

	private static long nullSafe(Long value) {
		return value == null ? 0L : value;
	}

	private static List<String> csv(String raw) {
		List<String> values = new ArrayList<>();
		for (String part : raw.split(",")) {
			if (!part.isBlank()) {
				values.add(part.trim());
			}
		}
		return values;
	}

	private static String required(String key) {
		String value = System.getProperty(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("-D" + key + " is required");
		}
		return value;
	}

	private static Path optionalPath(String key) {
		String value = System.getProperty(key, "");
		return value.isBlank() ? null : Path.of(value);
	}
}
