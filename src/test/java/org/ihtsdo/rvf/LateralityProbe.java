package org.ihtsdo.rvf;

import org.ihtsdo.otf.snomedboot.factory.LoadingProfile;
import org.ihtsdo.otf.sqs.service.ReleaseImportManager;
import org.ihtsdo.otf.sqs.service.SnomedQueryService;
import org.ihtsdo.otf.sqs.service.store.ReleaseStore;
import org.ihtsdo.rvf.core.service.RF2ReleaseTypeUnpacker;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Proves that MRCM's lateralizable-domain check can be answered with two ECL
 * queries instead of one per candidate concept, and that the answer is
 * identical.
 *
 * <p>{@code ValidationService.processLateralizableDomainConstraintQuery} runs
 * {@code eclQueryReturnConceptIdentifiers(">" + conceptId)} inside a loop over
 * every concept carrying {@code 272741003 |Laterality|} - 25,997 of them on the
 * AU edition. Each of those is a distinct query STRING, so none of it can be
 * cached and every iteration pays a fresh ECL parse. It is also serial, and it
 * tests membership with {@code ArrayList<Long>.contains}, which is a linear
 * scan over boxed Longs.
 *
 * <p>The rule is that a concept may carry Laterality only if it is, or descends
 * from, a member of the lateralizable body structure refset. So
 * {@code ancestors(c) INTERSECT members != {}} is exactly
 * {@code c IN descendantOrSelfOf(members)}, which one {@code <<} query answers
 * for all candidates at once. The service supports that form - the constraint
 * itself used to be written {@code << ^ 723264001} before the 20180731 release,
 * per the comment on the method being replaced.
 *
 * <p>Reports both timings and asserts the two violated sets are equal, so the
 * rewrite is justified by measurement rather than by reading.
 *
 * <pre>
 *   java -cp ... org.ihtsdo.rvf.LateralityProbe /path/to/edition.zip
 * </pre>
 */
public final class LateralityProbe {

	private static final String LATERALITY_ATTRIBUTE = "272741003";
	private static final String LATERALIZABLE_REFSET = "723264001";
	/** The domain constraint as MRCM ships it: members of the refset. */
	private static final String MEMBERS = "^ " + LATERALIZABLE_REFSET;

	public static void main(String[] args) throws Exception {
		if (args.length < 1) {
			System.err.println("usage: LateralityProbe <release.zip>");
			System.exit(2);
		}
		File zip = new File(args[0]);
		if (!zip.isFile()) {
			System.err.println("no such release: " + zip);
			System.exit(2);
		}

		long t0 = System.currentTimeMillis();
		Path dir = RF2ReleaseTypeUnpacker.unpack(zip,
				Path.of(System.getProperty("java.io.tmpdir")), "Snapshot");
		System.out.printf("  unpacked in  : %.0fs -> %s%n", (System.currentTimeMillis() - t0) / 1000.0, dir);

		// Exactly the INFERRED profile the library uses, copied from
		// ValidationService.getSnomedQueryService:187 - a profile that differs
		// would make the comparison measure the wrong index.
		LoadingProfile profile = LoadingProfile.light
				.withRefsets(LATERALIZABLE_REFSET)
				.withoutStatedAttributeMapOnConcept()
				.withInactiveConcepts()
				.withoutIdentifiers();
		t0 = System.currentTimeMillis();
		ReleaseStore store = new ReleaseImportManager()
				.loadReleaseFilesToMemoryBasedIndex(dir.toFile(), profile);
		SnomedQueryService q = new SnomedQueryService(store);
		System.out.printf("  index built  : %.0fs%n", (System.currentTimeMillis() - t0) / 1000.0);

		List<Long> withAttribute = q.eclQueryReturnConceptIdentifiers("*:" + LATERALITY_ATTRIBUTE + "=*", 0, -1)
				.conceptIds();
		System.out.printf("  candidates   : %,d concepts carry Laterality%n", withAttribute.size());

		// ---- as it is today: one ancestor query per candidate ----
		t0 = System.currentTimeMillis();
		List<Long> membersList = q.eclQueryReturnConceptIdentifiers(MEMBERS, 0, -1).conceptIds();
		Set<Long> oldViolated = new LinkedHashSet<>();
		long queries = 1;
		for (Long conceptId : withAttribute) {
			if (membersList.contains(conceptId)) {      // linear, as upstream
				continue;
			}
			List<Long> ancestors = q.eclQueryReturnConceptIdentifiers(">" + conceptId, 0, -1).conceptIds();
			queries++;
			if (ancestors.stream().noneMatch(membersList::contains)) {
				oldViolated.add(conceptId);
			}
		}
		long oldMs = System.currentTimeMillis() - t0;
		System.out.printf("  PER-CONCEPT  : %,d queries, %.1fs, %,d violated%n",
				queries, oldMs / 1000.0, oldViolated.size());

		// ---- set-based: descendantOrSelfOf(members), one query ----
		t0 = System.currentTimeMillis();
		Set<Long> allowed = new HashSet<>(
				q.eclQueryReturnConceptIdentifiers("<< " + MEMBERS, 0, -1).conceptIds());
		Set<Long> newViolated = new LinkedHashSet<>();
		for (Long conceptId : withAttribute) {
			if (!allowed.contains(conceptId)) {
				newViolated.add(conceptId);
			}
		}
		long newMs = System.currentTimeMillis() - t0;
		System.out.printf("  SET-BASED    : 2 queries, %.1fs, %,d violated  (allowed set %,d)%n",
				newMs / 1000.0, newViolated.size(), allowed.size());

		// ---- the only thing that licenses the change ----
		List<Long> onlyOld = new ArrayList<>(oldViolated);
		onlyOld.removeAll(newViolated);
		List<Long> onlyNew = new ArrayList<>(newViolated);
		onlyNew.removeAll(oldViolated);
		System.out.printf("  only per-concept: %d   only set-based: %d%n", onlyOld.size(), onlyNew.size());
		if (!onlyOld.isEmpty() || !onlyNew.isEmpty()) {
			System.out.printf("  DIVERGED - sample only-old %s only-new %s%n",
					onlyOld.subList(0, Math.min(5, onlyOld.size())),
					onlyNew.subList(0, Math.min(5, onlyNew.size())));
			System.out.println("  VERDICT: NOT equivalent, do not ship");
			System.exit(1);
		}
		System.out.printf("  VERDICT: identical, speedup %.1fx%n", oldMs / (double) Math.max(newMs, 1));
		store.destroy();
	}
}
