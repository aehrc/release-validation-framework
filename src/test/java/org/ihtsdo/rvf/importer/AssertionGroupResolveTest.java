package org.ihtsdo.rvf.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.ihtsdo.rvf.core.data.model.Assertion;
import org.junit.jupiter.api.Test;

/**
 * Group selection is what stops a corpus running assertions that belong to
 * someone else. Unfiltered, the IHTSDO corpus spent 84% of its findings on ten
 * other countries' preferred-term assertions when pointed at an Australian
 * release, so this is load-bearing rather than tidy.
 */
class AssertionGroupResolveTest {

	private static final String GROUPS = """
			<?xml version="1.0" encoding="UTF-8"?>
			<assertionGroupingStrategy>
			  <group name="AustralianEdition" excludeByPolicy="stated-relationship"
			         includeStandaloneCategories="file-centric-validation,component-centric-validation,AU"/>
			  <group name="IrishEdition"
			         includeStandaloneCategories="file-centric-validation,extension-only,IE"/>
			  <group name="au-authoring" includeByPolicy="category-centre-AU"/>
			</assertionGroupingStrategy>
			""";

	// Attributes, not child elements - policies.xml packs each list into one
	// attribute (assertionUuids, assertionTextPhrases, categoryWithCentrePairs).
	// Writing this fixture with child elements produced a policy that matched
	// nothing and an exclusion that silently never fired, which is exactly the
	// failure this test exists to catch.
	private static final String POLICIES = """
			<?xml version="1.0" encoding="UTF-8"?>
			<policyValues>
			  <policy name="stated-relationship"
			          assertionUuids="11111111-1111-1111-1111-111111111111"/>
			  <policy name="category-centre-AU"
			          categoryWithCentrePairs="release-type-validation,AU|file-centric-validation,AU"/>
			</policyValues>
			""";

	@Test
	void anotherEditionsAssertionIsNotSelectedForAustralia() {
		Assertion irish = assertion("22222222-2222-2222-2222-222222222222",
				"Every active concept has one active preferred term in the Irish dialect.",
				"file-centric-validation,IE,extension-only");
		Assertion australian = assertion("33333333-3333-3333-3333-333333333333",
				"AMT concepts are members of the MPUU refset.", "file-centric-validation,AU");

		Map<String, Set<String>> groups = resolve(List.of(irish, australian));

		assertTrue(groups.get(irish.getUuid().toString()).contains("IrishEdition"));
		// It carries file-centric-validation, which AustralianEdition also
		// includes - so this passes only because the standalone-category match
		// is what selects it, not because IE was excluded by name.
		assertTrue(groups.get(australian.getUuid().toString()).contains("AustralianEdition"));
		assertFalse(groups.get(australian.getUuid().toString()).contains("IrishEdition"),
				"an AU assertion has no business in the Irish group");
	}

	@Test
	void excludeByPolicyBeatsAnIncludingCategory() {
		// A stated-relationship assertion carries file-centric-validation, so the
		// include path would take it; the exclusion has to win. Getting this
		// backwards would run the assertions that OOMed the nightly.
		Assertion stated = assertion("11111111-1111-1111-1111-111111111111",
				"The stated relationship file is an accurate derivative.", "file-centric-validation,AU");

		Set<String> groups = resolve(List.of(stated)).get(stated.getUuid().toString());

		assertFalse(groups.contains("AustralianEdition"));
		assertTrue(groups.contains("au-authoring"),
				"a group without that exclusion still selects it");
	}

	@Test
	void anAssertionMatchingNothingBelongsToNoGroup() {
		Assertion orphan = assertion("44444444-4444-4444-4444-444444444444",
				"Build a table of concepts edited this cycle.", "resource");

		assertEquals(Set.of(), resolve(List.of(orphan)).get(orphan.getUuid().toString()),
				"resource assertions are infrastructure and sit in no group - which is why "
				+ "filtering by group alone drops the tables other assertions need");
	}

	private static Map<String, Set<String>> resolve(List<Assertion> assertions) {
		return new AssertionGroupImporter(null).resolveGroups(
				new ByteArrayInputStream(GROUPS.getBytes(StandardCharsets.UTF_8)),
				new ByteArrayInputStream(POLICIES.getBytes(StandardCharsets.UTF_8)),
				assertions);
	}

	private static Assertion assertion(String uuid, String text, String keywords) {
		Assertion a = new Assertion();
		a.setUuid(UUID.fromString(uuid));
		a.setAssertionText(text);
		a.setKeywords(keywords);
		return a;
	}
}
