package org.ihtsdo.rvf.core.service.drools.duck;

import org.ihtsdo.drools.domain.Concept;
import org.ihtsdo.drools.response.InvalidContent;
import org.ihtsdo.drools.validator.rf2.DroolsRF2Validator;
import org.ihtsdo.otf.resourcemanager.ManualResourceConfiguration;
import org.ihtsdo.otf.resourcemanager.ResourceConfiguration;
import org.ihtsdo.otf.resourcemanager.ResourceManager;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;

/**
 * What does a full-snapshot Drools run cost, and what does an authored-only run
 * cost, on the same dataset with the same rules?
 *
 * <p>Both scopes share one DuckDB dataset, so the build cost is paid once and
 * reported separately - otherwise it would be double-counted into whichever
 * scope ran first and make the comparison meaningless.
 *
 * <p>Deliberately reports the rule breakdown too. A scope change that makes the
 * run fast by validating nothing useful is a regression, not an optimisation,
 * and only the per-rule counts show the difference.
 *
 * <pre>
 *   mvn -o exec:java -Dexec.classpathScope=test \
 *     -Dexec.mainClass=org.ihtsdo.rvf.core.service.drools.duck.ScopeTimingHarness \
 *     -Dexec.args="&lt;rf2-dir&gt; &lt;rules-dir&gt; &lt;effectiveTime&gt; &lt;ruleSets csv&gt;"
 * </pre>
 */
public class ScopeTimingHarness {

	public static void main(String[] args) throws Exception {
		if (args.length < 4) {
			System.err.println("usage: ScopeTimingHarness <rf2-dir> <rules-dir> <effectiveTime> <ruleSets csv>");
			System.exit(64);
		}
		Set<String> dirs = Set.of(args[0]);
		Set<String> ruleSets = new LinkedHashSet<>(List.of(args[3].split(",")));

		System.out.println("release   : " + args[0]);
		System.out.println("rule sets : " + ruleSets);
		System.out.println("max heap  : " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");

		DroolsRF2Validator validator = new DroolsRF2Validator(args[1], blank());

		long t0 = System.currentTimeMillis();
		try (DuckDroolsDataset dataset = new DuckDroolsDataset(dirs, args[2])) {
			long buildMs = System.currentTimeMillis() - t0;

			DuckConceptService concepts = new DuckConceptService(dataset);
			DuckDescriptionService descriptions = new DuckDescriptionService(dataset, concepts,
					validator.getRuleExecutor().newTestResourceProvider(blank()));
			DuckRelationshipService relationships = new DuckRelationshipService(dataset);

			String editionEt = dataset.editionEffectiveTime();
			System.out.printf("%ndataset build : %,d ms   edition effectiveTime: %s%n", buildMs, editionEt);

			// Authored first: it is the fast one, so a mistake surfaces in seconds
			// rather than after the full-snapshot run has burned an hour.
			if (editionEt != null) {
				run(validator, ruleSets, concepts.authoredConcepts(editionEt),
						concepts, descriptions, relationships, "AUTHORED (" + editionEt + ")");
			} else {
				System.out.println("no module dependency refset - cannot scope to authored");
			}
			run(validator, ruleSets, concepts.allConcepts(),
					concepts, descriptions, relationships, "FULL SNAPSHOT");
		}
	}

	private static void run(DroolsRF2Validator validator, Set<String> ruleSets,
							Collection<Concept> scope, DuckConceptService c,
							DuckDescriptionService d, DuckRelationshipService r, String label) {
		System.out.printf("%n=== %s: %,d concepts ===%n", label, scope.size());
		long t0 = System.currentTimeMillis();
		List<InvalidContent> found = validator.getRuleExecutor()
				.execute(ruleSets, null, scope, c, d, r, true, true);
		long ms = System.currentTimeMillis() - t0;
		System.out.printf("  %,d ms (%.1f min)   %,d violations%n", ms, ms / 60000.0, found.size());

		TreeMap<String, Integer> byRule = new TreeMap<>();
		int errors = 0;
		for (InvalidContent v : found) {
			String m = v.getMessage() == null ? "?" : v.getMessage();
			// Runtime failures masquerade as violations - a missing Lucene method
			// produced 548,345 of 548,350 "failures" on custom-rvf 15861. Counting
			// them as content would make any comparison meaningless.
			if (m.startsWith("An error occurred while running")) {
				errors++;
				continue;
			}
			byRule.merge(m.length() > 80 ? m.substring(0, 80) : m, 1, Integer::sum);
		}
		System.out.printf("  runtime errors (NOT content): %,d%n", errors);
		byRule.entrySet().stream()
				.sorted((a, b) -> b.getValue() - a.getValue())
				.limit(12)
				.forEach(e -> System.out.printf("    %,8d  %s%n", e.getValue(), e.getKey()));
	}

	private static ResourceManager blank() {
		return new ResourceManager(new ManualResourceConfiguration(
				true, false, new ResourceConfiguration.Local(""), null), new DefaultResourceLoader());
	}
}
