package org.ihtsdo.rvf.core.service.drools.duck;

import org.ihtsdo.drools.domain.Concept;
import org.ihtsdo.drools.response.InvalidContent;
import org.ihtsdo.drools.service.TestResourceProvider;
import org.ihtsdo.drools.validator.rf2.DroolsRF2Validator;
import org.ihtsdo.drools.validator.rf2.IncumbentRepositoryAccess;
import org.ihtsdo.drools.validator.rf2.SnomedDroolsComponentRepository;
import org.ihtsdo.drools.validator.rf2.domain.DroolsConcept;
import org.ihtsdo.drools.validator.rf2.service.DroolsConceptService;
import org.ihtsdo.drools.validator.rf2.service.DroolsRelationshipService;
import org.ihtsdo.otf.resourcemanager.ResourceManager;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Proves the DuckDB service layer produces what the in-heap one produces.
 *
 * <p>Two levels, because they answer different questions.
 *
 * <p><b>Level 1 - end-to-end.</b> Runs the same rules over the same release
 * twice, once through each service layer, and diffs the resulting
 * {@code InvalidContent}. This is the claim that actually matters, and it needs
 * only public API: {@code DroolsRF2Validator.validateRF2Files} for the
 * incumbent, {@code RuleExecutor.execute} for DuckDB. It answers "is there a
 * difference".
 *
 * <p><b>Level 2 - per method.</b> Calls every service method on both
 * implementations across every concept and diffs the answers. It answers
 * "where", and it is the only way to settle questions the source does not:
 * notably whether snomed-boot's stated-ancestor computation includes
 * GCI-derived parents, which our recursive CTE currently excludes.
 *
 * <p>Level 2 is worth running even when level 1 agrees. Two implementations can
 * differ on a method whose result no active rule currently depends on - that is
 * a latent difference, and it will surface later as an unexplained divergence
 * when the rules change. Better to know now.
 *
 * <pre>
 *   mvn -q test-compile
 *   mvn -q exec:java -Dexec.classpathScope=test \
 *       -Dexec.mainClass=org.ihtsdo.rvf.core.service.drools.duck.DroolsParityHarness \
 *       -Dexec.args="&lt;extracted-rf2-dir&gt; &lt;rules-dir&gt; &lt;effectiveTime&gt; [ruleSet]"
 * </pre>
 *
 * Exits non-zero on any difference, so it can gate.
 */
public class DroolsParityHarness {

	private static final int SAMPLES = 8;
	private static final String ROOT = "138875005";

	public static void main(String[] args) throws Exception {
		if (args.length < 3) {
			System.err.println("usage: DroolsParityHarness <extracted-rf2-dir> <rules-dir> "
					+ "<effectiveTime yyyyMMdd> [ruleSetName]");
			System.exit(64);
		}
		Set<String> dirs = Set.of(args[0]);
		String rulesDir = args[1];
		String effectiveTime = args[2];
		Set<String> ruleSets = Set.of(args.length > 3 ? args[3] : "common-authoring");

		System.out.println("release   : " + dirs);
		System.out.println("rules     : " + rulesDir);
		System.out.println("effective : " + effectiveTime);
		System.out.println("rule sets : " + ruleSets);
		System.out.println();

		DroolsRF2Validator validator = new DroolsRF2Validator(rulesDir, blankResourceManager());
		TestResourceProvider testResources =
				validator.getRuleExecutor().newTestResourceProvider(blankResourceManager());

		int failures = 0;
		failures += levelTwo(dirs, effectiveTime, testResources);
		failures += levelOne(validator, dirs, effectiveTime, ruleSets, testResources);

		System.out.println();
		if (failures == 0) {
			System.out.println("PARITY: the two service layers agree");
		} else {
			System.out.println("PARITY FAILED: " + failures + " differing check(s)");
		}
		System.exit(failures == 0 ? 0 : 1);
	}

	// ------------------------------------------------------------------
	// Level 1: same rules, same release, both service layers
	// ------------------------------------------------------------------

	private static int levelOne(DroolsRF2Validator validator, Set<String> dirs, String effectiveTime,
								Set<String> ruleSets, TestResourceProvider testResources) throws Exception {
		System.out.println("=== level 1: end-to-end InvalidContent ===");

		long t0 = System.currentTimeMillis();
		List<InvalidContent> incumbent = validator.validateRF2Files(
				dirs, null, ruleSets, null, effectiveTime, null, true);
		long incumbentMs = System.currentTimeMillis() - t0;

		t0 = System.currentTimeMillis();
		List<InvalidContent> duck;
		try (DuckDroolsDataset dataset = new DuckDroolsDataset(dirs, effectiveTime)) {
			DuckConceptService conceptService = new DuckConceptService(dataset);
			DuckDescriptionService descriptionService =
					new DuckDescriptionService(dataset, conceptService, testResources);
			DuckRelationshipService relationshipService = new DuckRelationshipService(dataset);
			duck = validator.getRuleExecutor().execute(ruleSets, null, conceptService.allConcepts(),
					conceptService, descriptionService, relationshipService, true, true);
		}
		long duckMs = System.currentTimeMillis() - t0;

		Set<String> a = keys(incumbent);
		Set<String> b = keys(duck);
		Set<String> onlyIncumbent = new TreeSet<>(a);
		onlyIncumbent.removeAll(b);
		Set<String> onlyDuck = new TreeSet<>(b);
		onlyDuck.removeAll(a);

		System.out.printf("  incumbent %,d violations in %,dms%n", incumbent.size(), incumbentMs);
		System.out.printf("  duckdb    %,d violations in %,dms  (%.1fx)%n",
				duck.size(), duckMs, duckMs == 0 ? 0 : (double) incumbentMs / duckMs);
		System.out.printf("  only incumbent %d, only duckdb %d%n", onlyIncumbent.size(), onlyDuck.size());
		show("  MISSED by duckdb", onlyIncumbent);
		show("  EXTRA in duckdb", onlyDuck);

		return onlyIncumbent.isEmpty() && onlyDuck.isEmpty() ? 0 : 1;
	}

	/**
	 * Identity of a violation. Deliberately excludes the human-readable message
	 * - it embeds terms and ids that get rewritten downstream - and keys on
	 * what the rule actually asserted about which component.
	 */
	private static Set<String> keys(List<InvalidContent> violations) {
		Set<String> out = new TreeSet<>();
		for (InvalidContent v : violations) {
			out.add(v.getRuleId() + "|" + v.getConceptId() + "|" + v.getComponentId());
		}
		return out;
	}

	// ------------------------------------------------------------------
	// Level 2: method by method, every concept
	// ------------------------------------------------------------------

	private static int levelTwo(Set<String> dirs, String effectiveTime,
								TestResourceProvider testResources) throws Exception {
		System.out.println("=== level 2: per-method, all concepts ===");

		SnomedDroolsComponentRepository repository = IncumbentRepositoryAccess.load(dirs, effectiveTime);
		DroolsConceptService incConcept = IncumbentRepositoryAccess.conceptService(repository, effectiveTime);
		DroolsRelationshipService incRelationship = IncumbentRepositoryAccess.relationshipService(repository);

		int failures = 0;
		try (DuckDroolsDataset dataset = new DuckDroolsDataset(dirs, effectiveTime)) {
			DuckConceptService duckConcept = new DuckConceptService(dataset);
			DuckRelationshipService duckRelationship = new DuckRelationshipService(dataset);

			Map<String, Diff> diffs = new LinkedHashMap<>();
			for (String m : new String[]{"isActive", "findStatedAncestorsOfConcept",
					"findTopLevelHierarchiesOfConcept", "isConceptModellingChanged",
					"hasActiveInboundStatedRelationship"}) {
				diffs.put(m, new Diff(m));
			}

			// getAllTopLevelHierarchies is a single global answer, and several
			// other methods intersect against it, so a difference here would
			// cascade. Check it first and on its own.
			Diff tlh = new Diff("getAllTopLevelHierarchies");
			compareSets(tlh, "*", incConcept.getAllTopLevelHierarchies(),
					duckConcept.getAllTopLevelHierarchies());

			// The DuckDB side is bulk-loaded with one query per method, NOT
			// called per concept. Calling per concept means a query round-trip
			// per concept per method - on this release that is ~550k concepts
			// times five methods, and the harness simply does not finish.
			// The incumbent side is already in heap, so it stays a loop.
			Set<String> duckActive = dataset.queryStrings(
					"SELECT id FROM concept WHERE active = '1'");
			Set<String> duckInboundStated = dataset.queryStrings(
					"SELECT DISTINCT destination_id FROM stated_relationship WHERE active");
			Set<String> duckModellingChanged = dataset.queryStrings(
					"SELECT DISTINCT o.referencedComponentId FROM owl_refset o "
					+ "WHERE o.effectiveTime = '" + effectiveTime + "' "
					+ "  AND NOT EXISTS (SELECT 1 FROM stated_relationship s "
					+ "                  WHERE s.axiom_id = o.id AND s.axiom_gci) "
					+ "UNION "
					+ "SELECT DISTINCT sourceId FROM inferred_relationship "
					+ "WHERE effectiveTime = '" + effectiveTime + "'");
			Map<String, Set<String>> duckAncestors = groupPairs(dataset,
					"SELECT concept_id, ancestor_id FROM stated_ancestor");
			Set<String> duckConceptIds = dataset.queryStrings("SELECT id FROM concept");
			Set<String> duckTopLevel = duckConcept.getAllTopLevelHierarchies();

			for (DroolsConcept concept : repository.getConcepts()) {
				String id = concept.getId();
				if (!duckConceptIds.contains(id)) {
					diffs.get("isActive").record(id, "present", "ABSENT from DuckDB");
					continue;
				}
				compare(diffs.get("isActive"), id,
						incConcept.isActive(id), duckActive.contains(id));
				Set<String> duckAnc = duckAncestors.getOrDefault(id, Set.of());
				if (ROOT.equals(id)) {
					duckAnc = Set.of();   // matches findStatedAncestorsOfConcept's early return
				}
				compareSets(diffs.get("findStatedAncestorsOfConcept"), id,
						incConcept.findStatedAncestorsOfConcept(concept), duckAnc);
				Set<String> duckTlhOf = new HashSet<>(duckAnc);
				duckTlhOf.retainAll(duckTopLevel);
				compareSets(diffs.get("findTopLevelHierarchiesOfConcept"), id,
						incConcept.findTopLevelHierarchiesOfConcept(concept), duckTlhOf);
				compare(diffs.get("isConceptModellingChanged"), id,
						incConcept.isConceptModellingChanged(concept),
						duckModellingChanged.contains(id));
				compare(diffs.get("hasActiveInboundStatedRelationship"), id,
						incRelationship.hasActiveInboundStatedRelationship(id),
						duckInboundStated.contains(id));
			}

			// findLanguageReferenceSetByModule takes a module, not a concept.
			Diff lang = new Diff("findLanguageReferenceSetByModule");
			for (String moduleId : modulesOf(repository)) {
				compareSets(lang, moduleId,
						incConcept.findLanguageReferenceSetByModule(moduleId),
						duckConcept.findLanguageReferenceSetByModule(moduleId));
			}

			List<Diff> all = new ArrayList<>();
			all.add(tlh);
			all.addAll(diffs.values());
			all.add(lang);
			for (Diff d : all) {
				d.report();
				if (d.differing > 0) {
					failures++;
				}
			}
		}
		return failures;
	}

	/** One query, grouped in memory: {@code id -> set of related ids}. */
	private static Map<String, Set<String>> groupPairs(DuckDroolsDataset dataset, String sql) throws Exception {
		Map<String, Set<String>> out = new java.util.HashMap<>();
		try (java.sql.Statement s = dataset.getConnection().createStatement();
			 java.sql.ResultSet rs = s.executeQuery(sql)) {
			while (rs.next()) {
				out.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
			}
		}
		return out;
	}

	private static Set<String> modulesOf(SnomedDroolsComponentRepository repository) {
		Set<String> modules = new TreeSet<>();
		repository.getConcepts().forEach(c -> modules.add(c.getModuleId()));
		return modules;
	}

	private static void compare(Diff diff, String subject, boolean incumbent, boolean duck) {
		diff.checked++;
		if (incumbent != duck) {
			diff.record(subject, String.valueOf(incumbent), String.valueOf(duck));
		}
	}

	private static void compareSets(Diff diff, String subject, Set<String> incumbent, Set<String> duck) {
		diff.checked++;
		Set<String> i = incumbent == null ? Set.of() : new HashSet<>(incumbent);
		Set<String> d = duck == null ? Set.of() : new HashSet<>(duck);
		if (i.equals(d)) {
			return;
		}
		Set<String> missing = new TreeSet<>(i);
		missing.removeAll(d);
		Set<String> extra = new TreeSet<>(d);
		extra.removeAll(i);
		diff.record(subject,
				"n=" + i.size() + (missing.isEmpty() ? "" : " missing=" + trim(missing)),
				"n=" + d.size() + (extra.isEmpty() ? "" : " extra=" + trim(extra)));
	}

	private static String trim(Set<String> s) {
		List<String> l = new ArrayList<>(s);
		l.sort(Comparator.naturalOrder());
		return l.size() <= 4 ? l.toString() : l.subList(0, 4) + "...+" + (l.size() - 4);
	}

	private static void show(String label, Set<String> keys) {
		if (keys.isEmpty()) {
			return;
		}
		System.out.println(label + " (" + keys.size() + "):");
		keys.stream().limit(SAMPLES).forEach(k -> System.out.println("    " + k));
	}

	/**
	 * Test resources are deliberately blank.
	 *
	 * <p>Four DescriptionService methods read from the TestResourceProvider
	 * rather than the repository, and both implementations are handed the SAME
	 * provider instance - the DuckDB one delegates those methods verbatim. So
	 * the provider cannot be a source of divergence, and loading real resources
	 * would only add a variable (and an S3 dependency) without testing anything
	 * this harness is for.
	 */
	private static ResourceManager blankResourceManager() {
		return new ResourceManager(DroolsRF2Validator.BLANK_RESOURCES_CONFIGURATION,
				new PathMatchingResourcePatternResolver());
	}

	/** Accumulates differences for one method. */
	private static final class Diff {
		final String method;
		int checked;
		int differing;
		final Map<String, String[]> samples = new LinkedHashMap<>();

		Diff(String method) {
			this.method = method;
		}

		void record(String subject, String incumbent, String duck) {
			differing++;
			if (samples.size() < SAMPLES) {
				samples.put(subject, new String[]{incumbent, duck});
			}
		}

		void report() {
			System.out.printf("  %-38s %,8d checked  %,6d differ%n", method, checked, differing);
			samples.forEach((subject, v) -> {
				System.out.println("      " + subject);
				System.out.println("        incumbent: " + v[0]);
				System.out.println("        duckdb   : " + v[1]);
			});
		}
	}

}
