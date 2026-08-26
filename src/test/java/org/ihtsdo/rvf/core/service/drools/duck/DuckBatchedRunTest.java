package org.ihtsdo.rvf.core.service.drools.duck;

import org.ihtsdo.drools.domain.Concept;
import org.ihtsdo.drools.response.InvalidContent;
import org.ihtsdo.drools.validator.rf2.DroolsRF2Validator;
import org.ihtsdo.otf.resourcemanager.ManualResourceConfiguration;
import org.ihtsdo.otf.resourcemanager.ResourceConfiguration;
import org.ihtsdo.otf.resourcemanager.ResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The guard on {@link DuckBatchedRun}: batching must not change the findings.
 *
 * <p>A batch boundary is invisible in the output. A rule that quietly stops
 * firing across one produces a report that is clean, plausible and smaller -
 * there is nothing to notice, which is exactly the failure mode that needs a
 * test rather than an argument.
 *
 * <p>The fixture is built for the rule that would break first. Two active
 * concepts share an FSN, which is what "an FSN must be unique within all active
 * FSNs across all active concepts" exists to catch. At {@code batchSize=1} the
 * two concepts are validated in SEPARATE Drools sessions, so the finding
 * survives only because the rule resolves the other side through
 * {@code descriptionService.findActiveDescriptionByExactTerm} - a query over
 * the whole release - rather than through a second working-memory fact. If a
 * future rule joins two in-memory Description patterns across concepts, this
 * test goes red instead of the report going quietly short.
 */
class DuckBatchedRunTest {

	private static final String MODULE = "900000000000207008";
	private static final String FSN = "900000000000003001";
	private static final String SYNONYM = "900000000000013009";
	private static final String PRIMITIVE = "900000000000074008";
	private static final String CASE_INSENSITIVE = "900000000000448009";
	private static final String ROOT = "138875005";
	private static final String IS_A = "116680003";
	private static final String US_EN = "900000000000509007";
	private static final String PREFERRED = "900000000000548007";

	/** The term deliberately duplicated across two concepts. */
	private static final String SHARED = "Duplicated widget (product)";

	@TempDir
	Path release;

	@Test
	void batchingDoesNotChangeTheFindings() throws Exception {
		Path rules = Path.of("snomed-drools-rules");
		Path testResources = Path.of("test-resources");
		assumeTrue(Files.isDirectory(rules) && Files.isDirectory(testResources),
				"rules and test resources not checked out - run ./checkout-resources.sh");

		writeRelease();

		DroolsRF2Validator validator = new DroolsRF2Validator(rules.toString(), local(testResources));
		Set<String> ruleSets = Set.of("common-authoring");

		try (DuckDroolsDataset dataset = new DuckDroolsDataset(
				Set.of(release.toAbsolutePath().toString()), "20260831")) {

			List<String> oneBatch = run(validator, ruleSets, dataset, 100);
			List<String> perConcept = run(validator, ruleSets, dataset, 1);

			// Non-empty first: two runs that both found nothing would compare
			// equal and prove nothing at all.
			assertFalse(oneBatch.isEmpty(),
					"fixture produced no findings, so the comparison below is vacuous");
			// The comparison above is only meaningful if a CROSS-CONCEPT rule
			// actually fired: same-concept rules cannot notice a batch boundary,
			// so a fixture that only triggers those would pass no matter how
			// broken batching was. This is the finding that spans the boundary.
			assertTrue(oneBatch.stream().anyMatch(f -> f.contains("must be unique")),
					"fixture did not trigger the cross-concept FSN uniqueness rule, so it "
							+ "does not exercise a batch boundary at all. Findings: " + oneBatch);

			assertEquals(oneBatch, perConcept,
					"batching changed the findings. A rule is joining two working-memory "
							+ "facts across concepts, so it only fires when both land in the "
							+ "same batch - see the class javadoc.");
		}
	}

	/**
	 * The regression test for a memo that its own caller mutated.
	 *
	 * <p>{@code findTopLevelHierarchiesOfConcept} calls {@code retainAll} on what
	 * {@code findStatedAncestorsOfConcept} returns, which is legitimate - the
	 * incumbent hands back a fresh mutable set. Returning the cache entry instead
	 * meant the first call truncated the memo to the top level hierarchies, and
	 * every later ancestor query for that concept answered from the wreckage.
	 *
	 * <p>Order is the whole test. Ask, narrow, ask again - the second ask must
	 * match the first. The parity harness compared both methods across all
	 * 722,404 concepts and saw nothing, because it runs one method at a time and
	 * so did every ancestor comparison before any entry was corrupted.
	 */
	@Test
	void narrowingTheAncestorsDoesNotCorruptTheCache() throws Exception {
		writeRelease();
		try (DuckDroolsDataset dataset = new DuckDroolsDataset(
				Set.of(release.toAbsolutePath().toString()), "20260831")) {
			DuckConceptService concepts = new DuckConceptService(dataset);
			Concept c = concepts.findById("100000001");

			// A COPY, not the returned set. When the defect is present the
			// returned set IS the cache entry, so retainAll below mutates it
			// too - and comparing it against the later read compares an object
			// with itself, which passes no matter how broken the cache is.
			Set<String> before = new HashSet<>(concepts.findStatedAncestorsOfConcept(c));
			assertFalse(before.isEmpty(), "fixture concept has no stated ancestors, so this "
					+ "test cannot detect the truncation it exists for");

			concepts.findTopLevelHierarchiesOfConcept(c);

			assertEquals(before, concepts.findStatedAncestorsOfConcept(c),
					"the ancestor set changed after findTopLevelHierarchiesOfConcept ran - "
							+ "it is handing out the cache entry rather than a copy");
		}
	}

	/** One full run at a given batch size, findings flattened and sorted for comparison. */
	private List<String> run(DroolsRF2Validator validator, Set<String> ruleSets,
			DuckDroolsDataset dataset, int batchSize) throws Exception {
		DuckConceptService concepts = new DuckConceptService(dataset);
		DuckDescriptionService descriptions = new DuckDescriptionService(dataset, concepts,
				validator.getRuleExecutor().newTestResourceProvider(local(Path.of("test-resources"))));
		DuckRelationshipService relationships = new DuckRelationshipService(dataset);

		Collection<Concept> scope = concepts.allConcepts();
		List<InvalidContent> found = DuckBatchedRun.execute(validator.getRuleExecutor(),
				ruleSets, null, scope, concepts, descriptions, relationships, true, true, batchSize);

		List<String> keys = new ArrayList<>();
		for (InvalidContent v : found) {
			keys.add(v.getSeverity() + "|" + v.getConceptId() + "|" + v.getComponentId()
					+ "|" + v.getMessage());
		}
		keys.sort(null);
		return keys;
	}

	/**
	 * A minimal RF2 Snapshot. Only the files
	 * {@link DuckDroolsDataset} treats as required plus the refsets these rules
	 * navigate; everything else legitimately resolves to an empty relation.
	 */
	private void writeRelease() throws IOException {
		Path snapshot = Files.createDirectories(release.resolve("Snapshot").resolve("Terminology"));
		Path refset = Files.createDirectories(release.resolve("Snapshot").resolve("Refset"));

		write(snapshot.resolve("sct2_Concept_Snapshot_TEST.txt"),
				"id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId",
				row(ROOT, "20260101", "1", MODULE, PRIMITIVE),
				// RuleExecutor.checkComponentsIntegrity resolves the type of every
				// relationship it is given, so IS_A has to exist as a concept.
				row(IS_A, "20260101", "1", MODULE, PRIMITIVE),
				row("100000001", "", "1", MODULE, PRIMITIVE),
				row("100000002", "", "1", MODULE, PRIMITIVE));

		write(snapshot.resolve("sct2_Description_Snapshot_TEST.txt"),
				"id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\tcaseSignificanceId",
				row("200000001", "20260101", "1", MODULE, ROOT, "en", FSN,
						"SNOMED CT Concept (SNOMED RT+CTV3)", CASE_INSENSITIVE),
				row("200000002", "20260101", "1", MODULE, IS_A, "en", FSN,
						"Is a (attribute)", CASE_INSENSITIVE),
				// The duplication under test: same FSN term, two active concepts.
				row("200000011", "", "1", MODULE, "100000001", "en", FSN, SHARED, CASE_INSENSITIVE),
				row("200000012", "", "1", MODULE, "100000001", "en", SYNONYM,
						"Duplicated widget", CASE_INSENSITIVE),
				row("200000021", "", "1", MODULE, "100000002", "en", FSN, SHARED, CASE_INSENSITIVE),
				row("200000022", "", "1", MODULE, "100000002", "en", SYNONYM,
						"Duplicated widget", CASE_INSENSITIVE));

		write(snapshot.resolve("sct2_Relationship_Snapshot_TEST.txt"),
				"id\teffectiveTime\tactive\tmoduleId\tsourceId\tdestinationId\trelationshipGroup"
						+ "\ttypeId\tcharacteristicTypeId\tmodifierId",
				row("300000001", "", "1", MODULE, "100000001", ROOT, "0", IS_A,
						"900000000000011006", "900000000000451002"),
				row("300000002", "", "1", MODULE, "100000002", ROOT, "0", IS_A,
						"900000000000011006", "900000000000451002"));

		write(refset.resolve("der2_cRefset_LanguageSnapshot_TEST.txt"),
				"id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\tacceptabilityId",
				lang("400000001", "200000001"), lang("400000002", "200000002"),
				lang("400000011", "200000011"),
				lang("400000012", "200000012"), lang("400000021", "200000021"),
				lang("400000022", "200000022"));

		// Stated relationships are derived from axioms, so the hierarchy has to
		// come from OWL rather than from the Relationship file.
		write(refset.resolve("sct2_sRefset_OWLExpressionSnapshot_TEST.txt"),
				"id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId\towlExpression",
				row("500000001", "", "1", MODULE, "733073007", "100000001",
						"SubClassOf(:100000001 :" + ROOT + ")"),
				row("500000002", "", "1", MODULE, "733073007", "100000002",
						"SubClassOf(:100000002 :" + ROOT + ")"));

		write(refset.resolve("der2_ssRefset_ModuleDependencySnapshot_TEST.txt"),
				"id\teffectiveTime\tactive\tmoduleId\trefsetId\treferencedComponentId"
						+ "\tsourceEffectiveTime\ttargetEffectiveTime",
				row("600000001", "20260831", "1", MODULE, "900000000000534007",
						"900000000000012004", "20260831", "20260831"));
	}

	private static String lang(String id, String descriptionId) {
		return row(id, "", "1", MODULE, US_EN, descriptionId, PREFERRED);
	}

	private static String row(String... cells) {
		return String.join("\t", cells);
	}

	private static void write(Path file, String header, String... rows) throws IOException {
		StringBuilder sb = new StringBuilder(header).append('\n');
		for (String r : rows) {
			sb.append(r).append('\n');
		}
		Files.writeString(file, sb);
	}

	/**
	 * ResourceManager NPEs on an absolute local path - {@code normalisePath}
	 * strips the leading slash - so the path stays relative, as it does in
	 * {@link ScopeTimingHarness}.
	 */
	private static ResourceManager local(Path dir) {
		String path = dir.toString();
		return new ResourceManager(new ManualResourceConfiguration(
				true, false, new ResourceConfiguration.Local(
						path.endsWith("/") ? path : path + "/"), null),
				new DefaultResourceLoader());
	}
}
