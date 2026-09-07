package org.ihtsdo.rvf.drools;

import org.ihtsdo.drools.RuleExecutor;
import org.ihtsdo.drools.RuleExecutorFactory;
import org.ihtsdo.drools.domain.Concept;
import org.ihtsdo.drools.domain.Constants;
import org.ihtsdo.drools.domain.OntologyAxiom;
import org.ihtsdo.drools.response.InvalidContent;
import org.ihtsdo.drools.rulestestrig.TestUtil;
import org.ihtsdo.drools.rulestestrig.domain.TestAnnotation;
import org.ihtsdo.drools.rulestestrig.domain.TestComponent;
import org.ihtsdo.drools.rulestestrig.domain.TestConcept;
import org.ihtsdo.drools.rulestestrig.domain.TestDescription;
import org.ihtsdo.drools.rulestestrig.domain.TestOntologyAxiom;
import org.ihtsdo.drools.rulestestrig.domain.TestRelationship;
import org.ihtsdo.drools.rulestestrig.service.TestConceptService;
import org.ihtsdo.drools.rulestestrig.service.TestDescriptionService;
import org.ihtsdo.drools.rulestestrig.service.TestRelationshipService;
import org.ihtsdo.drools.service.TestResourceProvider;
import org.ihtsdo.otf.resourcemanager.ManualResourceConfiguration;
import org.ihtsdo.otf.resourcemanager.ResourceConfiguration;
import org.ihtsdo.otf.resourcemanager.ResourceManager;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every Drools rule we deploy, against the test cases shipped beside it.
 *
 * <p>The Drools phase contributes 78 of the nightly's 1,482 records and had no
 * gate in this build at all. Upstream owns one - {@code RulesTestManual} in
 * IHTSDO/snomed-drools - and it does not run here for three reasons, none of
 * them about the rules: it lives in another repository's test sources, it is
 * named {@code *Manual} so surefire skips it, it is JUnit 4 where this build
 * excludes junit-vintage, and it resolves the rules through the relative path
 * {@code ../../snomed-drools-rules}.
 *
 * <p>So the support classes are COPIES, verbatim and Apache-2.0, under their
 * original package {@code org.ihtsdo.drools.rulestestrig} - unchanged so they
 * can be re-synced with a diff - and this class is the driver: it resolves the
 * rules from the clone {@code checkout-resources.sh} pins, and reports one
 * dynamic test per rule directory rather than one pass/fail for 117 of them.
 *
 * <p>What it proves, per rule directory: every concept in
 * {@code assertConceptsPass} produces no finding, every concept in
 * {@code assertConceptsFail} produces at least one, no component/message pair
 * is reported twice, and one rule id never carries two different messages -
 * which is how the rule set's ids stay usable as whitelist keys.
 *
 * <p>Why it is worth running on the pinned clone rather than trusting upstream's
 * own CI: the rules are checked out by ref and BAKED INTO the image, and the
 * ref moves. A rule set that upstream fixed after our pin, or a rule that only
 * misbehaves against the test resources we ship, is invisible upstream and
 * live here.
 */
class DroolsRuleTestCasesTest {

	/**
	 * Where checkout-resources.sh puts the pinned rules.
	 *
	 * <p>Overridable with {@code -Ddrools.rules.dir}, for two reasons. A
	 * candidate ref can be gated BEFORE the pin moves, which is the only way to
	 * find out what a rules bump would break without bumping it. And the clone
	 * cannot be edited in place to test anything: {@code checkout-resources.sh}
	 * runs as a build phase and reverts it, which silently defeated my first
	 * attempt to prove this test fails when a rule stops firing.
	 */
	private static final Path RULES = Path.of(
			System.getProperty("drools.rules.dir", "snomed-drools-rules"));

	/**
	 * The four files the rules read through TestResourceProvider - case-
	 * significant words, semantic tags and their hierarchy, and the US/GB terms
	 * map. Upstream's dummy set, copied: the real ones are 3.8MB of
	 * spelling dictionary that nothing here needs, and using the real ones would
	 * make these results depend on a resource bucket.
	 */
	private static final String TEST_RESOURCES = "src/test/resources/drools-test-resources";

	private static final String GIVEN_CONCEPTS = "givenConcepts";

	/**
	 * Rule directories that assert nothing, and why. Committed rather than
	 * inferred: "no test cases" is either a placeholder with no behaviour or a
	 * real gap, and only a person can say which.
	 */
	private static final Path UNTESTED =
			Path.of("src/test/resources/drools/rules-without-test-cases.tsv");
	private static final String ASSERT_PASS = "assertConceptsPass";
	private static final String ASSERT_FAIL = "assertConceptsFail";

	/**
	 * Rule id to the start of its message, shared across every directory in one
	 * run. Upstream keeps this per test instance, which cannot see a collision
	 * between two rule sets - and a collision is exactly what breaks a whitelist
	 * entry, since a whitelist is keyed on the rule id.
	 */
	private final Map<String, String> ruleIdToMessage = new HashMap<>();

	@TestFactory
	List<DynamicTest> everyRuleDirectoryMatchesItsTestCases() throws Exception {
		assumeTrue(Files.isDirectory(RULES),
				RULES + " is not checked out - run ./checkout-resources.sh");

		List<Path> ruleDirectories = ruleDirectories();
		assertTrue(ruleDirectories.size() > 100,
				"only " + ruleDirectories.size() + " rule directories found - the clone "
						+ "looks wrong, and an empty rule set validates nothing while passing");

		Map<String, String> untested = untestedRules();
		Set<String> claimedUntested = new TreeSet<>(untested.keySet());

		// -Ddrools.rules.only=<substring> runs one directory, which is how a
		// rule being written gets a fast loop rather than the whole set.
		String only = System.getProperty("drools.rules.only", "");

		List<DynamicTest> tests = new ArrayList<>();
		for (Path directory : ruleDirectories) {
			String name = RULES.relativize(directory).toString();
			if (!only.isBlank() && !name.contains(only)) {
				continue;
			}
			tests.add(DynamicTest.dynamicTest(name,
					() -> check(directory, untested.containsKey(name), claimedUntested)));
		}
		if (!only.isBlank()) {
			assertTrue(!tests.isEmpty(), "-Ddrools.rules.only=" + only + " matched nothing");
			return tests;
		}
		// Last, so it sees what every case above found. A directory listed as
		// untested that now has cases has to come off the list, or the list
		// becomes a claim nobody rechecks - the same reason the store gate and
		// the assertion-error gate are both bidirectional.
		tests.add(DynamicTest.dynamicTest("the untested-rules list is not stale", () ->
				assertTrue(claimedUntested.isEmpty(),
						"these are listed in rules-without-test-cases.tsv but now have test "
								+ "cases - remove them: " + claimedUntested)));
		return tests;
	}

	/** Rule directory to the reason it asserts nothing, from the committed list. */
	private static Map<String, String> untestedRules() throws Exception {
		Map<String, String> out = new TreeMap<>();
		for (String line : Files.readAllLines(UNTESTED)) {
			if (line.isBlank() || line.startsWith("#")) {
				continue;
			}
			String[] fields = line.split("\t", 2);
			out.put(fields[0], fields.length > 1 ? fields[1] : "");
		}
		return out;
	}

	/**
	 * The three-level walk upstream does: product group, rule group, rule.
	 *
	 * <p>Sorted, so a failure is reproducible and two runs report in the same
	 * order. A directory with no .drl is not a rule directory - the clone has
	 * several holding only a README.
	 */
	private static List<Path> ruleDirectories() throws Exception {
		List<Path> out = new ArrayList<>();
		for (File productGroup : listDirectories(RULES.toFile())) {
			for (File ruleGroup : listDirectories(productGroup)) {
				for (File rule : listDirectories(ruleGroup)) {
					if (Objects.requireNonNull(rule.listFiles(TestUtil.RULE_FILE_FILTER)).length > 0) {
						out.add(rule.toPath());
					}
				}
			}
		}
		out.sort(Path::compareTo);
		return out;
	}

	private static File[] listDirectories(File parent) {
		File[] found = parent.listFiles(TestUtil.DIRECTORY_FILTER);
		return found == null ? new File[0] : found;
	}

	private void check(Path ruleDirectory, boolean listedUntested, Set<String> claimedUntested)
			throws Exception {
		File testCases = ruleDirectory.resolve("test-cases.json").toFile();
		assertTrue(testCases.isFile(), ruleDirectory + " has rules but no test-cases.json, so "
				+ "nothing states what they are supposed to find");

		Map<String, List<TestConcept<TestDescription, TestAnnotation, TestRelationship>>> cases =
				TestUtil.loadConceptMap(testCases);
		setConceptIdReferencesAndTempIds(cases);

		Map<String, Concept> given = new TreeMap<>();
		for (TestConcept<TestDescription, TestAnnotation, TestRelationship> concept
				: cases.getOrDefault(GIVEN_CONCEPTS, List.of())) {
			assertNotNull(concept.getId(),
					"concepts in '" + GIVEN_CONCEPTS + "' must have an id");
			given.put(concept.getId(), concept);
		}

		// One executor per directory, with the rule set named OneRule, exactly as
		// upstream does: the rules under test are the ones in THIS directory, so
		// a rule firing here cannot be another directory's.
		RuleExecutor executor = new RuleExecutorFactory()
				.createRuleExecutor(ruleDirectory.toAbsolutePath().toString(), "OneRule");
		TestResourceProvider resources = executor.newTestResourceProvider(
				new ResourceManager(new ManualResourceConfiguration(
						true, false, new ResourceConfiguration.Local(TEST_RESOURCES), null), null));

		TestConceptService conceptService = new TestConceptService(given);
		TestDescriptionService descriptionService = new TestDescriptionService(given, resources);
		TestRelationshipService relationshipService = new TestRelationshipService(given);

		List<TestConcept<TestDescription, TestAnnotation, TestRelationship>> shouldPass =
				cases.get(ASSERT_PASS);
		List<TestConcept<TestDescription, TestAnnotation, TestRelationship>> shouldFail =
				cases.get(ASSERT_FAIL);
		assertNotNull(shouldPass, "'" + ASSERT_PASS + "' is required");
		assertNotNull(shouldFail, "'" + ASSERT_FAIL + "' is required");
		// A directory whose rules are never exercised in either direction is a
		// rule nobody has stated the behaviour of: it passes this test whatever
		// the rule does. Allowed only where the committed list says why - 13 are
		// per-edition AlwaysPasses placeholders with no behaviour to assert, and
		// three are real upstream gaps.
		String name = RULES.relativize(ruleDirectory).toString();
		if (shouldPass.isEmpty() && shouldFail.isEmpty()) {
			assertTrue(listedUntested, name + " ships rules and asserts nothing about them. "
					+ "If that is intended, name it in "
					+ UNTESTED.getFileName() + " with the reason.");
			// The claim is true, so it is not stale. What survives in this set
			// is a directory the list calls untested which now has cases.
			claimedUntested.remove(name);
			return;
		}

		run(executor, conceptService, descriptionService, relationshipService, shouldPass, true);
		run(executor, conceptService, descriptionService, relationshipService, shouldFail, false);
	}

	private void run(RuleExecutor executor, TestConceptService conceptService,
			TestDescriptionService descriptionService,
			TestRelationshipService relationshipService,
			List<TestConcept<TestDescription, TestAnnotation, TestRelationship>> concepts,
			boolean expectPass) {
		for (TestConcept<TestDescription, TestAnnotation, TestRelationship> concept : concepts) {
			List<InvalidContent> findings = executor.execute(
					Set.of("OneRule"), null, Collections.singleton(concept),
					conceptService, descriptionService, relationshipService, false, true);

			Set<String> seen = new HashSet<>();
			for (InvalidContent finding : findings) {
				String pair = finding.getComponent().getId() + " " + finding.getMessage();
				assertTrue(seen.add(pair),
						"the same component and message is reported twice: " + pair);
				// Not uniqueness proof - coverage is not total - but it catches
				// the case that matters: one rule id used for two different
				// messages makes a whitelist entry mean two things.
				String previous = ruleIdToMessage.putIfAbsent(
						finding.getRuleId(), firstPart(finding.getMessage()));
				assertTrue(previous == null
								|| previous.equals(firstPart(finding.getMessage())),
						"rule id " + finding.getRuleId() + " carries two messages: '"
								+ previous + "' and '" + firstPart(finding.getMessage()) + "'");
			}

			if (expectPass) {
				assertEquals(0, findings.size(),
						"a concept from " + ASSERT_PASS + " failed: " + findings);
			} else {
				assertTrue(!findings.isEmpty(),
						"a concept from " + ASSERT_FAIL + " passed: " + concept);
			}
		}
	}

	/**
	 * Upstream's fixups, needed before the rules see the concepts: a test case
	 * writes a concept's descriptions and relationships without repeating the
	 * concept id on each, and may omit ids entirely.
	 */
	private static void setConceptIdReferencesAndTempIds(
			Map<String, List<TestConcept<TestDescription, TestAnnotation, TestRelationship>>> cases) {
		for (List<TestConcept<TestDescription, TestAnnotation, TestRelationship>> concepts
				: cases.values()) {
			for (TestConcept<TestDescription, TestAnnotation, TestRelationship> concept : concepts) {
				setTempIdIfMissing(concept);
				String id = concept.getId();
				for (TestRelationship relationship : concept.getRelationships()) {
					setTempIdIfMissing(relationship);
					relationship.setSourceId(id);
				}
				for (TestDescription description : concept.getDescriptions()) {
					setTempIdIfMissing(description);
					description.setConceptId(id);
					if (description.isTextDefinition()) {
						description.setTypeId(Constants.TEXT_DEFINITION);
					}
				}
				for (TestAnnotation annotation : concept.getAnnotations()) {
					setTempIdIfMissing(annotation);
					annotation.setConceptId(id);
				}
				for (OntologyAxiom axiom : concept.getOntologyAxioms()) {
					TestOntologyAxiom testAxiom = (TestOntologyAxiom) axiom;
					setTempIdIfMissing(testAxiom);
					testAxiom.setReferencedComponentId(id);
				}
			}
		}
	}

	private static void setTempIdIfMissing(TestComponent component) {
		if (component.getId() == null) {
			component.setId("temp-id-" + UUID.randomUUID());
		}
	}

	private static String firstPart(String message) {
		return message.substring(0, Math.min(message.length(), 20));
	}
}
