package org.ihtsdo.rvf.core.service.duck;

import org.ihtsdo.rvf.core.data.model.Assertion;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The corpus a DuckDB run executes, with no database to read it from.
 *
 * <p>Two fixtures on purpose. The synthetic groups.xml pins the behaviour this
 * class is responsible for; the real one - the corpus the build checks out
 * beside the project - pins that the rules it delegates to still select what
 * production expects from files nobody here controls.
 */
class DuckAssertionSourceTest {

	/**
	 * Keywords and severities copied in shape from the real corpus, because both
	 * are what group resolution matches on: "resource" alone, a bare default
	 * category, and a category paired with a centre are three distinct cases.
	 */
	private static final String STORE = """
			{
			 "formatVersion": 1,
			 "generator": {"tool": "publish_store.py", "corpus": "test"},
			 "sentinels": [{"placeholder": "<RUNID>", "sentinel": "424242424242424242"}],
			 "tableColumns": {},
			 "ports": [],
			 "prerequisites": [],
			 "assertions": {
			  "994b5ff0-79b9-11e1-b0c4-0800200c9a66": {
			   "file": "stated.sql",
			   "text": "The stated relationship file is an accurate derivative of the release.",
			   "keywords": "file-centric-validation,AU", "severity": "ERROR",
			   "statements": ["select 1"]},
			  "33333333-3333-3333-3333-333333333333": {
			   "file": "amt.sql", "text": "AMT concepts are members of the MPUU refset.",
			   "keywords": "file-centric-validation,AU", "severity": "ERROR",
			   "statements": ["select 2"]},
			  "22222222-2222-2222-2222-222222222222": {
			   "file": "irish.sql", "text": "Every active concept has an Irish preferred term.",
			   "keywords": "file-centric-validation,extension-only,IE", "severity": "WARNING",
			   "statements": ["select 3"]},
			  "44444444-4444-4444-4444-444444444444": {
			   "file": "pre.sql", "text": "Build the tables the other assertions need.",
			   "keywords": "resource", "severity": "",
			   "statements": ["select 4"]},
			  "55555555-5555-5555-5555-555555555555": {
			   "file": "intres.sql", "text": "Build the INT-only tables.",
			   "keywords": "resource,INT", "severity": "",
			   "statements": ["select 5"]}
			 }
			}
			""";

	private static final String GROUPS = """
			<?xml version="1.0" encoding="UTF-8"?>
			<assertionGroupingStrategy>
			  <group name="AustralianEdition" excludeByPolicy="stated-relationship"
			         includeStandaloneCategories="file-centric-validation,AU"/>
			  <group name="IrishEdition"
			         includeStandaloneCategories="file-centric-validation,extension-only,IE"/>
			  <group name="au-authoring" includeByPolicy="category-centre-AU"/>
			</assertionGroupingStrategy>
			""";

	private static final String POLICIES = """
			<?xml version="1.0" encoding="UTF-8"?>
			<policyValues>
			  <policy name="stated-relationship"
			          assertionUuids="994b5ff0-79b9-11e1-b0c4-0800200c9a66"/>
			  <policy name="category-centre-AU"
			          categoryWithCentrePairs="release-type-validation,AU|file-centric-validation,AU"/>
			</policyValues>
			""";

	@Test
	void assertionsCarryTheMetadataTheReportNeeds() throws IOException {
		Assertion amt = source().getAssertionByUuid(
				UUID.fromString("33333333-3333-3333-3333-333333333333"));

		// Every one of these lands in a TestRunItem, so a null here is a report
		// column that silently empties rather than a failure anyone notices.
		assertEquals("AMT concepts are members of the MPUU refset.", amt.getAssertionText());
		assertEquals("file-centric-validation,AU", amt.getKeywords());
		assertEquals("ERROR", amt.getSeverity());
	}

	@Test
	void theNumericAssertionIdIsLeftNullRatherThanInvented() throws IOException {
		// A MySQL AUTO_INCREMENT value assigned by a startup import that does not
		// happen in this mode. Synthesising one would produce an identity that
		// joins to nothing and differs between two runs of the same corpus, so
		// it stays absent - loudly, via this test, rather than by omission.
		assertNull(source().findAll().get(0).getAssertionId());
	}

	@Test
	void assertionsAreSelectedByGroupName() throws IOException {
		List<Assertion> au = source().getAssertionsInGroups(List.of("AustralianEdition"));

		assertEquals(List.of("AMT concepts are members of the MPUU refset."),
				au.stream().map(Assertion::getAssertionText).toList(),
				"the Irish assertion has no business in the Australian group, and the "
				+ "stated-relationship one is excluded by policy despite matching a category");
	}

	@Test
	void severalGroupsAreUnionedAndDeduplicated() throws IOException {
		// MysqlValidationService asks for a LIST of group names and unions the
		// assertions of every group it gets back into a Set. An assertion in two
		// of them must be executed once.
		List<Assertion> both = source().getAssertionsInGroups(
				List.of("AustralianEdition", "au-authoring"));

		assertEquals(2, both.size());
		assertEquals(2, both.stream().map(Assertion::getUuid).distinct().count());
	}

	@Test
	void anUnknownGroupNameSelectsNothingAndDoesNotThrow() throws IOException {
		// getAssertionGroupsByNames skips names it cannot find, so a run against
		// a misspelled group reports zero findings and passes. Matched here
		// rather than fixed, because diverging would be a second behaviour to
		// explain; populatedGroupNames is what makes it diagnosable.
		assertEquals(List.of(), source().getAssertionsInGroups(List.of("NoSuchEdition")));
		assertTrue(source().populatedGroupNames().contains("AustralianEdition"));
	}

	@Test
	void resourceAssertionsMatchTheWholeKeywordsFieldNotOneToken() throws IOException {
		// findAssertionsByKeywords is a derived query on the COLUMN, so the MySQL
		// path's ("resource", true) does not select "resource,INT". Every run
		// makes this call to build the shared tables before anything else, so
		// treating it as a token match would silently run extra setup.
		List<Assertion> exact = source().getAssertionsByKeyWords("resource", true);
		assertEquals(List.of("Build the tables the other assertions need."),
				exact.stream().map(Assertion::getAssertionText).toList());

		assertEquals(2, source().getAssertionsByKeyWords("resource", false).size(),
				"the inexact form is a substring match over the field");
	}

	@Test
	void groupMembershipIsAlsoCarriedOnTheAssertion() throws IOException {
		// MysqlFailuresExtractor reads Assertion.getGroups() to decide whether a
		// failure is whitelist-eligible. Null there is a NullPointerException in
		// the middle of report assembly.
		Assertion amt = source().getAssertionByUuid(
				UUID.fromString("33333333-3333-3333-3333-333333333333"));
		assertEquals(java.util.Set.of("AustralianEdition", "au-authoring"), amt.getGroups());

		Assertion resource = source().getAssertionByUuid(
				UUID.fromString("44444444-4444-4444-4444-444444444444"));
		assertEquals(java.util.Set.of(), resource.getGroups(),
				"resource assertions sit in no group - which is why filtering by group "
				+ "alone drops the tables the others need");
	}

	@Test
	void theRealCorpusGroupingFilesSelectWhatProductionExpects() throws IOException {
		// The build checks the assertion corpus out beside the project; skipped
		// rather than failed when it has not, so this file stays runnable alone.
		Path corpus = Path.of("snomed-release-validation-assertions");
		assumeTrue(Files.isReadable(corpus.resolve("groups.xml")),
				"assertion corpus not checked out");

		DuckAssertionSource source;
		try (InputStream groups = Files.newInputStream(corpus.resolve("groups.xml"));
				InputStream policies = Files.newInputStream(corpus.resolve("policies.xml"))) {
			source = DuckAssertionSource.from(DuckStore.parse(STORE), groups, policies);
		}

		assertTrue(source.groupsOf(UUID.fromString("33333333-3333-3333-3333-333333333333"))
				.contains("AustralianEdition"));
		assertFalse(source.groupsOf(UUID.fromString("22222222-2222-2222-2222-222222222222"))
				.contains("AustralianEdition"),
				"an IE assertion in the Australian group is the 84%-of-findings failure");
		// This uuid is genuinely in the real stated-relationship policy, so the
		// exclusion is being read from the shipped file and not from a fixture.
		assertFalse(source.groupsOf(UUID.fromString("994b5ff0-79b9-11e1-b0c4-0800200c9a66"))
				.contains("AustralianEdition"));
		assertTrue(source.groupsOf(UUID.fromString("994b5ff0-79b9-11e1-b0c4-0800200c9a66"))
				.contains("stated-relationships-release-validation"),
				"a group without that exclusion still selects it");
	}

	private static DuckAssertionSource source() throws IOException {
		return DuckAssertionSource.from(DuckStore.parse(STORE), stream(GROUPS), stream(POLICIES));
	}

	private static InputStream stream(String xml) {
		return new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8));
	}
}
