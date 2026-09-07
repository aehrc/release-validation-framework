package org.ihtsdo.rvf;

import org.ihtsdo.otf.snomedboot.factory.LoadingProfile;
import org.ihtsdo.otf.sqs.service.ReleaseImportManager;
import org.ihtsdo.otf.sqs.service.SnomedQueryService;
import org.ihtsdo.otf.sqs.service.store.ReleaseStore;
import org.ihtsdo.rvf.core.service.RF2ReleaseTypeUnpacker;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gate for replacing MRCM's out-of-range query form.
 *
 * <p>{@code SnomedQueryService.processQueryWithNotEqualTo} expresses "this
 * attribute has some value outside set S" as the complement of S rendered as
 * one exclusive range per member, concatenated into a query STRING. The classic
 * parser compiles an automaton per clause, which is where an AU MRCM run's heap
 * goes: 4.0M {@code TermRangeQuery} and 10.94 GB of transition tables at the
 * peak, plus 12.9% of the phase spent parsing query text.
 *
 * <p>The same documents are selected by naming the terms actually present in
 * that field and subtracting S - one {@code TermInSetQuery}, no automata - since
 * only terms present in the index can match. This runs every real out-of-range
 * expression taken from a production run BOTH ways against ONE index, and
 * compares the concept id sets exactly. Equality is the only thing that
 * licenses the change; the timings are secondary.
 *
 * <pre>
 *   java -cp ... org.ihtsdo.rvf.RangeSetProbe &lt;release.zip&gt; &lt;expressions.txt&gt;
 * </pre>
 */
public final class RangeSetProbe {

	private static final String RANGE_FORM = "sqs.notin.rangeform";

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: RangeSetProbe <release.zip> <expressions.txt>");
			System.exit(2);
		}
		File zip = new File(args[0]);
		List<String> expressions = Files.readAllLines(Path.of(args[1])).stream()
				.map(String::trim).filter(s -> !s.isEmpty()).toList();

		Path dir = RF2ReleaseTypeUnpacker.unpack(zip,
				Path.of(System.getProperty("java.io.tmpdir")), "Snapshot");

		// The INFERRED profile, per ValidationService.getSnomedQueryService:187.
		LoadingProfile profile = LoadingProfile.light
				.withRefsets("723264001")
				.withoutStatedAttributeMapOnConcept()
				.withInactiveConcepts()
				.withoutIdentifiers();

		long t0 = System.currentTimeMillis();
		ReleaseStore store = new ReleaseImportManager()
				.loadReleaseFilesToMemoryBasedIndex(dir.toFile(), profile);
		SnomedQueryService q = new SnomedQueryService(store);
		System.out.printf("  index built  : %.0fs%n", (System.currentTimeMillis() - t0) / 1000.0);
		System.out.printf("  expressions  : %d%n%n", expressions.size());

		int compared = 0, diverged = 0, failedBoth = 0;
		long rangeMs = 0, setMs = 0;
		long rangePeak = 0, setPeak = 0;

		for (String ecl : expressions) {
			System.setProperty(RANGE_FORM, "true");
			Result range = run(q, ecl);
			rangePeak = Math.max(rangePeak, usedHeap());

			System.setProperty(RANGE_FORM, "false");
			Result set = run(q, ecl);
			setPeak = Math.max(setPeak, usedHeap());

			if (range.failed && set.failed) {
				failedBoth++;                       // unsupported either way
				continue;
			}
			if (range.failed != set.failed) {
				diverged++;
				System.out.printf("  DIVERGED (one form failed): range=%s set=%s%n    %s%n",
						range.error, set.error, trim(ecl));
				continue;
			}
			rangeMs += range.ms;
			setMs += set.ms;
			compared++;

			if (!range.ids.equals(set.ids)) {
				diverged++;
				Set<Long> onlyRange = new HashSet<>(range.ids);
				onlyRange.removeAll(set.ids);
				Set<Long> onlySet = new HashSet<>(set.ids);
				onlySet.removeAll(range.ids);
				System.out.printf("  DIVERGED: range %d ids, set %d ids, onlyRange %d, onlySet %d%n    %s%n",
						range.ids.size(), set.ids.size(), onlyRange.size(), onlySet.size(), trim(ecl));
			}
		}

		System.out.printf("%n  compared     : %d   unsupported both ways: %d%n", compared, failedBoth);
		System.out.printf("  RANGE form   : %.1fs total, peak used heap %,d MiB%n", rangeMs / 1000.0, rangePeak);
		System.out.printf("  TERMSET form : %.1fs total, peak used heap %,d MiB%n", setMs / 1000.0, setPeak);
		System.out.printf("  diverged     : %d%n", diverged);

		// An equality result is worthless unless the new path actually ran. The
		// first version of this probe passed on all 134 expressions while
		// silently falling back to the range chain in BOTH arms, because an
		// unidentified field returns "keep the old behaviour". Counted, not
		// assumed.
		long engaged = SnomedQueryService.termSetQueriesBuilt();
		System.out.printf("  term-set queries built: %d%n", engaged);
		if (engaged == 0) {
			System.out.println("  VERDICT: VACUOUS - the new path never ran, comparison proves nothing");
			store.destroy();
			System.exit(1);
		}
		System.out.println(diverged == 0
				? String.format("  VERDICT: identical on all %d, speedup %.1fx", compared,
						rangeMs / (double) Math.max(setMs, 1))
				: "  VERDICT: NOT equivalent, do not ship");
		store.destroy();
		System.exit(diverged == 0 ? 0 : 1);
	}

	private record Result(Set<Long> ids, long ms, boolean failed, String error) { }

	private static Result run(SnomedQueryService q, String ecl) {
		long t = System.currentTimeMillis();
		try {
			List<Long> ids = q.eclQueryReturnConceptIdentifiers(ecl, 0, -1).conceptIds();
			return new Result(new HashSet<>(ids), System.currentTimeMillis() - t, false, null);
		} catch (Exception e) {
			return new Result(new HashSet<>(), System.currentTimeMillis() - t, true,
					e.getClass().getSimpleName());
		}
	}

	/** Used heap in MiB after a collection, so the figure is retention. */
	private static long usedHeap() {
		Runtime r = Runtime.getRuntime();
		return (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
	}

	private static String trim(String s) {
		return s.length() > 110 ? s.substring(0, 110) + "..." : s;
	}
}
