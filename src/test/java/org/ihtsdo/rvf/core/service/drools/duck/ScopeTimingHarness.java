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
 *     -Dexec.args="&lt;rf2-dir&gt; &lt;rules-dir&gt; &lt;effectiveTime&gt; &lt;ruleSets csv&gt; &lt;test-resources-dir&gt;"
 * </pre>
 */
public class ScopeTimingHarness {

	public static void main(String[] args) throws Exception {
		if (args.length < 5) {
			// test-resources is REQUIRED, not optional. An empty ResourceManager
			// loads zero semantic tags and zero case-significant words, which
			// does not fail loudly - it disarms every rule that consults them
			// and reports the run as clean. The only reason that was ever
			// noticed is that cs_words.txt happens to be mandatory and throws.
			System.err.println("usage: ScopeTimingHarness <rf2-dir> <rules-dir> <effectiveTime>"
					+ " <ruleSets csv> <test-resources-dir>");
			System.exit(64);
		}
		Set<String> dirs = Set.of(args[0]);
		Set<String> ruleSets = new LinkedHashSet<>(List.of(args[3].split(",")));

		System.out.println("release   : " + args[0]);
		System.out.println("rule sets : " + ruleSets);
		System.out.println("max heap  : " + Runtime.getRuntime().maxMemory() / (1024 * 1024) + " MB");

		ResourceManager testResources = local(args[4]);
		System.out.println("resources : " + args[4]);
		DroolsRF2Validator validator = new DroolsRF2Validator(args[1], testResources);

		long t0 = System.currentTimeMillis();
		try (DuckDroolsDataset dataset = new DuckDroolsDataset(dirs, args[2])) {
			long buildMs = System.currentTimeMillis() - t0;

			DuckConceptService concepts = new DuckConceptService(dataset);
			DuckDescriptionService descriptions = new DuckDescriptionService(dataset, concepts,
					validator.getRuleExecutor().newTestResourceProvider(testResources));
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
			// -Dscope.only=authored skips the full-snapshot pass. Iterating on
			// the authored scope is seconds; the full pass is not, and running
			// it every time is the difference between a usable feedback loop
			// and one nobody uses.
			if (!"authored".equalsIgnoreCase(System.getProperty("scope.only", ""))) {
				run(validator, ruleSets, concepts.allConcepts(),
						concepts, descriptions, relationships, "FULL SNAPSHOT");
			}
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
		// A bare count is not actionable, and this is the number most likely to
		// be non-zero for the wrong reason: 548,345 of one run's 548,350
		// "failures" were a single missing Lucene method. Show what they are.
		if (errors > 0) {
			found.stream()
					.map(v -> v.getMessage() == null ? "?" : v.getMessage())
					.filter(m -> m.startsWith("An error occurred while running"))
					.collect(java.util.stream.Collectors.groupingBy(
							m -> m.length() > 160 ? m.substring(0, 160) : m,
							java.util.TreeMap::new, java.util.stream.Collectors.counting()))
					.entrySet().stream()
					.sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
					.limit(5)
					.forEach(e -> System.out.printf("    %,8d  %s%n", e.getValue(), e.getKey()));
		}
		byRule.entrySet().stream()
				.sorted((a, b) -> b.getValue() - a.getValue())
				.limit(12)
				.forEach(e -> System.out.printf("    %,8d  %s%n", e.getValue(), e.getKey()));
	}

	/**
	 * A local ResourceManager over {@code path}, relativised the same way
	 * {@code RulesCompileProbe} does - ResourceManager NPEs on an absolute
	 * local path, so a caller passing one would otherwise get a stack trace
	 * instead of the working behaviour.
	 */
	private static ResourceManager local(String path) {
		return new ResourceManager(new ManualResourceConfiguration(
				true, false, new ResourceConfiguration.Local(relativise(path)), null),
				new DefaultResourceLoader());
	}

	private static String relativise(String path) {
		if (path.isEmpty()) {
			return path;
		}
		java.nio.file.Path p = java.nio.file.Paths.get(path);
		if (!p.isAbsolute()) {
			return path.endsWith("/") ? path : path + "/";
		}
		java.nio.file.Path cwd = java.nio.file.Paths.get(System.getProperty("user.dir"));
		java.nio.file.Path rel = p.startsWith(cwd) ? cwd.relativize(p) : p;
		String out = rel.toString();
		return out.endsWith("/") ? out : out + "/";
	}
}
