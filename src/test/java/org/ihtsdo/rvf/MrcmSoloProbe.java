package org.ihtsdo.rvf;

import org.ihtsdo.rvf.core.service.RF2ReleaseTypeUnpacker;
import org.snomed.quality.validator.mrcm.Assertion;
import org.snomed.quality.validator.mrcm.ContentType;
import org.snomed.quality.validator.mrcm.ValidationRun;
import org.snomed.quality.validator.mrcm.ValidationService;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Times the MRCM phase on its own, and records what it costs in memory.
 *
 * <p>MRCM is the critical path of a full validation. On build 16226 the whole
 * run took 13m36s wall and MRCM finished last, so it alone very nearly defines
 * the wall clock - which makes it the only phase where a speed-up shortens the
 * run roughly one-for-one.
 *
 * <p>It is also the largest heap consumer. {@code MRCMValidationService} runs
 * the INFERRED and STATED forms concurrently, and each one independently builds
 * a full Lucene index of the edition through {@code RamReleaseStore}, which is
 * a {@code ByteBuffersDirectory} - entirely on the JVM heap. So two complete
 * indexes of a 722,404-concept edition are resident at once.
 *
 * <p>This probe exists so any change to that can be judged on both axes rather
 * than one. It reports wall time and peak heap for:
 *
 * <ul>
 *   <li>{@code concurrent} - what production does today: both forms at once.</li>
 *   <li>{@code sequential} - one form then the other. Available with no library
 *       change at all, so it is the cheap comparison to make first: if it costs
 *       little time it halves the index residency.</li>
 * </ul>
 *
 * <p>Usage:
 *
 * <pre>
 *   mvn -q test-compile
 *   java -XX:MaxRAMPercentage=75 -Xlog:gc -cp target/test-classes:target/classes:$CP \
 *        org.ihtsdo.rvf.MrcmSoloProbe /path/to/edition.zip [concurrent|sequential|both]
 * </pre>
 *
 * <p>Peak heap is sampled rather than derived from {@code Runtime.totalMemory},
 * because a committed heap says what the JVM asked the OS for, not what the run
 * needed. The sampler reads the collector's own post-collection used figure.
 */
public class MrcmSoloProbe {

	private static volatile long peakUsedBytes;
	private static volatile boolean sampling = true;

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("usage: MrcmSoloProbe <release.zip> [concurrent|sequential|both]");
			System.exit(2);
		}
		File zip = new File(args[0]);
		if (!zip.isFile()) {
			System.err.println("no such release: " + zip);
			System.exit(2);
		}
		String mode = args.length > 1 ? args[1] : "both";

		System.out.printf("release      : %s (%.0f MB)%n", zip.getName(), zip.length() / 1048576.0);
		System.out.printf("max heap     : %.1f GiB%n",
				Runtime.getRuntime().maxMemory() / 1073741824.0);
		System.out.printf("cores        : %d%n%n", Runtime.getRuntime().availableProcessors());

		// Extract once and reuse. Unpacking is ~30s and is not what is being
		// measured; leaving it inside the timed section would hide the thing
		// that is.
		long t0 = System.currentTimeMillis();
		Path extracted = RF2ReleaseTypeUnpacker.unpack(zip,
				Path.of(System.getProperty("java.io.tmpdir")), "Snapshot");
		Set<String> dirs = new HashSet<>();
		dirs.add(extracted.toAbsolutePath().toString());
		System.out.printf("unpacked in  : %.0fs -> %s%n",
				(System.currentTimeMillis() - t0) / 1000.0, extracted);
		System.out.printf("rf2 files    : %d%n%n", countFiles(extracted));

		if (mode.equals("both") || mode.equals("concurrent")) {
			run("concurrent", dirs, true);
		}
		if (mode.equals("both") || mode.equals("sequential")) {
			run("sequential", dirs, false);
		}
	}

	private static void run(String label, Set<String> dirs, boolean concurrent) throws Exception {
		// A fresh service and a fresh MRCM load per measurement, so the second
		// run does not benefit from the first's warm caches.
		ValidationService service = new ValidationService();
		ValidationRun base = new ValidationRun(null, null, false);
		base.setFullSnapshotRelease(true);

		long loadStart = System.currentTimeMillis();
		service.loadMRCM(dirs, base);
		long loadMs = System.currentTimeMillis() - loadStart;

		ValidationRun inferred = form(base, ContentType.INFERRED);
		ValidationRun stated = form(base, ContentType.STATED);

		startSampler();
		long start = System.currentTimeMillis();

		if (concurrent) {
			try (ExecutorService pool = Executors.newCachedThreadPool()) {
				List<Future<Void>> tasks = List.of(
						pool.submit(() -> { service.validateRelease(dirs, inferred); return null; }),
						pool.submit(() -> { service.validateRelease(dirs, stated); return null; }));
				for (Future<Void> t : tasks) {
					t.get();
				}
			}
		} else {
			service.validateRelease(dirs, inferred);
			service.validateRelease(dirs, stated);
		}

		long elapsed = System.currentTimeMillis() - start;
		long peak = stopSampler();

		System.out.printf("=== %s ===%n", label);
		System.out.printf("  loadMRCM     : %.1fs%n", loadMs / 1000.0);
		System.out.printf("  validate     : %.1fs  (%dm %02ds)%n",
				elapsed / 1000.0, elapsed / 60000, (elapsed / 1000) % 60);
		System.out.printf("  peak heap    : %.2f GiB%n", peak / 1073741824.0);
		System.out.printf("  assertions   : inferred %d, stated %d%n",
				inferred.getCompletedAssertions().size(), stated.getCompletedAssertions().size());
		digest(label, inferred, stated);
		System.out.println();
	}

	/**
	 * WHICH concepts each assertion flagged, not how many.
	 *
	 * <p>MRCM parity has been a pair of counts - "inferred 497, stated 481" -
	 * and a count is preserved by any change that swaps one finding for
	 * another. Two of this project's parity results were already wrong for
	 * related reasons: a probe reported 134 expressions identical while BOTH
	 * arms ran the old code, and a laterality comparison agreed because both
	 * forms returned zero violations. A digest over the sorted violated concept
	 * ids answers the question those numbers only appeared to.
	 *
	 * <p>Per assertion AND overall: the total digest changes if anything moves,
	 * and the per-assertion lines say what. Written to -Dmrcm.digest.out when
	 * set, so two runs - or two builds of the validator - can be diffed
	 * directly.
	 */
	private static void digest(String label, ValidationRun inferred, ValidationRun stated)
			throws Exception {
		StringBuilder out = new StringBuilder();
		MessageDigest all = MessageDigest.getInstance("SHA-256");
		long violations = 0;
		for (var pair : List.of(Map.entry("inferred", inferred), Map.entry("stated", stated))) {
			// Build every line first, then sort the LINES. Sorting the
			// assertions by uuid is not enough: getCompletedAssertions() holds
			// several entries per uuid - the same assertion under different
			// attributes - so uuid alone leaves ties, and the domain/attribute
			// checks now run on a pool, which is exactly where an arbitrary tie
			// order turns into a digest that changes run to run for no content
			// reason. A digest that moves without the content moving is worse
			// than no digest: it trains its reader to ignore it.
			List<String> lines = new ArrayList<>();
			for (Assertion a : pair.getValue().getCompletedAssertions()) {
				List<Long> ids = a.getCurrentViolatedConceptIds() == null
						? List.of() : a.getCurrentViolatedConceptIds().stream().sorted().toList();
				violations += ids.size();
				lines.add(pair.getKey() + "\t" + a.getUuid() + "\t"
						+ a.getFailureType() + "\t" + ids.size() + "\t"
						+ sha256(ids.stream().map(String::valueOf)
								.collect(Collectors.joining(","))));
			}
			lines.sort(Comparator.naturalOrder());
			for (String line : lines) {
				out.append(line).append('\n');
				all.update(line.getBytes(StandardCharsets.UTF_8));
			}
		}
		String overall = HexFormat.of().formatHex(all.digest());
		System.out.printf("  violations   : %d concept(s) across both forms%n", violations);
		System.out.printf("  DIGEST       : %s%n", overall);
		String path = System.getProperty("mrcm.digest.out");
		if (path != null) {
			Path file = Path.of(path.replace("%LABEL%", label));
			Files.writeString(file, "# form\tassertionUuid\tfailureType\tviolations\t"
					+ "sha256(sorted concept ids)\n# overall " + overall + "\n" + out);
			System.out.printf("  wrote        : %s%n", file);
		}
	}

	private static String sha256(String s) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(s.getBytes(StandardCharsets.UTF_8)));
	}

	private static ValidationRun form(ValidationRun base, ContentType type) {
		ValidationRun run = new ValidationRun(null, type, false);
		run.setFullSnapshotRelease(true);
		run.setMRCMDomains(base.getMRCMDomains());
		run.setAttributeRangesMap(base.getAttributeRangesMap());
		run.setUngroupedAttributes(base.getUngroupedAttributes());
		run.setConceptsUsedInMRCMTemplates(base.getConceptsUsedInMRCMTemplates());
		run.setLateralizableRefsetMembers(base.getLateralizableRefsetMembers());
		run.setAnatomyStructureAndEntireRefsets(base.getAnatomyStructureAndEntireRefsets());
		run.setAnatomyStructureAndPartRefsets(base.getAnatomyStructureAndPartRefsets());
		return run;
	}

	private static void startSampler() {
		peakUsedBytes = 0;
		sampling = true;
		Thread t = new Thread(() -> {
			var heap = ManagementFactory.getMemoryMXBean();
			while (sampling) {
				long used = heap.getHeapMemoryUsage().getUsed();
				if (used > peakUsedBytes) {
					peakUsedBytes = used;
				}
				try {
					TimeUnit.MILLISECONDS.sleep(250);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					return;
				}
			}
		}, "heap-sampler");
		t.setDaemon(true);
		t.start();
	}

	private static long stopSampler() throws InterruptedException {
		sampling = false;
		TimeUnit.MILLISECONDS.sleep(400);
		return peakUsedBytes;
	}

	private static long countFiles(Path dir) {
		try (var s = Files.list(dir)) {
			return s.filter(Files::isRegularFile).count();
		} catch (Exception e) {
			return -1;
		}
	}
}
