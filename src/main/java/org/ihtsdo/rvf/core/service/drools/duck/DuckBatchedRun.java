package org.ihtsdo.rvf.core.service.drools.duck;

import org.ihtsdo.drools.RuleExecutor;
import org.ihtsdo.drools.domain.Concept;
import org.ihtsdo.drools.response.InvalidContent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Runs the Drools rules over a scope in batches, holding one batch's object
 * graph at a time.
 *
 * <h2>Why this exists</h2>
 *
 * <p>{@link DuckConceptService#prefetch} is what makes the DuckDB backend
 * competitive: Drools' domain API is object-graph navigation, one accessor call
 * per component, which against a columnar engine is thousands of point lookups.
 * Loading the scope in six bulk scans and serving it from memory took the
 * MDRS-authored scope from 149 s to 35 s.
 *
 * <p>It does not survive contact with a whole edition. Measured on
 * {@code SnomedCT_ManagedServiceAU_DAILYBUILD_BETA_AU1000036_20260831T120000Z}
 * at {@code -Xmx8g}, prefetching all 722,404 concepts in one call loads
 * 2,265,530 descriptions and 7,581,616 relationships, pins the heap at 7.35 GB
 * of 8 GB and drives the JVM into near-continuous collection - one concurrent
 * mark cycle alone took 36 seconds. Throughput settles at roughly 10,000
 * concepts per 12 minutes, extrapolating to about 15 hours against the in-heap
 * backend's 788 s for the same content in the same heap. That is not a slow
 * backend; it is a backend that has been asked to hold a second copy of exactly
 * the structure prefetching was meant to avoid, alongside DuckDB's own memory.
 *
 * <p>So the unit of prefetching is a batch, not a run. Peak memory becomes a
 * function of {@link #DEFAULT_BATCH_SIZE} rather than of the edition, and the
 * derivation that DuckDB is genuinely good at - axiom conversion, transitive
 * closure, computing the scope at all - is paid once in the dataset build.
 *
 * <h2>Why batching does not change the findings</h2>
 *
 * <p>This is the part that has to be argued rather than assumed, because a
 * batch boundary is invisible in the output: a rule that silently stops firing
 * across it produces a clean, plausible, smaller report.
 *
 * <p>Rules that relate two components fall into two kinds. The first joins two
 * facts already in working memory - {@code d1 : Description(...)} against
 * {@code d2 : Description(...)}. Every such rule in {@code common-authoring}
 * and {@code au-authoring} constrains both sides to the same concept
 * ({@code conceptId == d1.conceptId}), so both facts are inserted together by
 * construction and no batch boundary can separate them. TermUnique,
 * MultiFsnInSameLanguage, TermCaseSignificance, DuplicateRelationships and
 * RedundantIsaRelationship are all of this kind.
 *
 * <p>The second kind genuinely spans concepts - "an FSN must be unique across
 * all active concepts", "an active FSN should not exist as an inactive term on
 * another concept", "two active descriptions in the same hierarchy should not
 * share a term". Every one of those obtains the other side through
 * {@code from descriptionService.findActiveDescriptionByExactTerm(...)},
 * {@code findInactiveDescriptionByExactTerm(...)} or
 * {@code findMatchingDescriptionInHierarchy(...)} - a query over the whole
 * release, not a second working-memory fact. The batch determines which
 * concepts are ACCUSED, never which concepts are SEARCHED.
 *
 * <p>{@link DuckBatchedRunTest} pins this empirically by running one scope at
 * two batch sizes and asserting the findings are identical, which is the check
 * that would catch a future rule of the first kind that is not same-concept.
 */
public final class DuckBatchedRun {

	private static final Logger LOGGER = LoggerFactory.getLogger(DuckBatchedRun.class);

	/**
	 * Concepts per batch.
	 *
	 * <p>Large enough that the fixed cost of a batch - materialising the scope
	 * table, six bulk scans, and the term memo warming from cold - is amortised;
	 * small enough that the object graph stays a bounded fraction of the heap.
	 * At 10,000 the prefetch holds roughly a seventieth of the edition.
	 */
	public static final int DEFAULT_BATCH_SIZE = 10_000;

	private DuckBatchedRun() {
	}

	public static List<InvalidContent> execute(RuleExecutor executor, Set<String> ruleSets,
			Set<String> excludedRuleSets, Collection<? extends Concept> scope,
			DuckConceptService concepts, DuckDescriptionService descriptions,
			DuckRelationshipService relationships,
			boolean includeInferredRelationships, boolean includePublishedComponents) {
		return execute(executor, ruleSets, excludedRuleSets, scope, concepts, descriptions,
				relationships, includeInferredRelationships, includePublishedComponents,
				DEFAULT_BATCH_SIZE);
	}

	public static List<InvalidContent> execute(RuleExecutor executor, Set<String> ruleSets,
			Set<String> excludedRuleSets, Collection<? extends Concept> scope,
			DuckConceptService concepts, DuckDescriptionService descriptions,
			DuckRelationshipService relationships,
			boolean includeInferredRelationships, boolean includePublishedComponents,
			int batchSize) {

		List<Concept> all = new ArrayList<>(scope);
		// A batch size at or above the scope is one batch, which is the
		// pre-existing behaviour rather than a special case.
		int size = batchSize > 0 ? batchSize : all.size();
		int batches = (all.size() + size - 1) / Math.max(size, 1);
		LOGGER.info("executing {} concepts in {} batch(es) of {}", all.size(), batches, size);

		List<InvalidContent> found = new ArrayList<>();
		long t0 = System.currentTimeMillis();
		for (int from = 0; from < all.size(); from += size) {
			List<Concept> batch = all.subList(from, Math.min(from + size, all.size()));
			try {
				concepts.prefetch(batch);
				found.addAll(executor.execute(ruleSets, excludedRuleSets, batch, concepts,
						descriptions, relationships, includeInferredRelationships,
						includePublishedComponents));
			} finally {
				// In a finally block deliberately: a batch that throws must not
				// leave its object graph pinned while the next one loads, or an
				// error at batch 3 of 73 becomes an OutOfMemoryError at batch 4
				// and the original cause is lost.
				concepts.releaseScope();
				descriptions.releaseScope();
			}
			// Heap is logged beside progress because it is the number this
			// class exists to control: if used memory climbs batch over batch,
			// something is retaining a scope and the fix has stopped working.
			Runtime rt = Runtime.getRuntime();
			LOGGER.info("batch {}/{}: {} concepts done, {} findings, {} s elapsed, heap {} MB used",
					(from / size) + 1, batches, Math.min(from + size, all.size()), found.size(),
					(System.currentTimeMillis() - t0) / 1000,
					(rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
		}
		return found;
	}
}
