package org.ihtsdo.rvf.rest.controller;

import org.ihtsdo.rvf.core.data.model.Assertion;
import org.ihtsdo.rvf.core.data.model.AssertionGroup;
import org.ihtsdo.rvf.core.service.AssertionService;
import org.ihtsdo.rvf.core.service.DroolsRulesValidationService;
import org.ihtsdo.rvf.core.service.TraceabilityComparisonService;
import org.ihtsdo.rvf.core.service.duck.DuckAssertionService;
import org.ihtsdo.rvf.rest.helper.AssertionLookup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /assertions} must not write to the corpus it is listing.
 *
 * <p>Reported by Attila: {@code getAssertionsAndJoinGroups} hands back whatever
 * {@code findAll()} returned and the endpoint then calls {@code addAll} on it
 * to append Drools rules, traceability assertions or SEP assertions. It is
 * written as if it owns a private copy.
 *
 * <p>On the MySQL engine it does own one - {@code findAll()} runs a query - so
 * this has never shown upstream. On the DuckDB engine the corpus is loaded once
 * and {@code DuckAssertionSource} holds it as {@code List.copyOf}, so the
 * {@code addAll} throws {@code UnsupportedOperationException} and the endpoint
 * answers 500. Anyone opening the assertions page with Drools rules included
 * gets an error, and the cause is three frames deeper than the request.
 *
 * <p>Note which way round the failure is, because it decides the fix: the
 * corpus being immutable is CORRECT and is what turned a silent corruption into
 * a loud error. Had {@code findAll()} handed out a mutable internal list, the
 * rules would have been appended to the live corpus, every subsequent
 * validation would have enumerated them, and the engine - which has no
 * statements for a Drools rule - would have reported each as "store and
 * assertion corpus are out of step". So the endpoint is what changes.
 */
class AssertionControllerOwnershipTest {

	private static Assertion assertion(String text, String keywords) {
		Assertion assertion = new Assertion();
		assertion.setUuid(UUID.randomUUID());
		assertion.setAssertionText(text);
		assertion.setKeywords(keywords);
		// As DuckAssertionSource leaves them: groups already resolved, which is
		// what makes the addGroup half of the report idempotent rather than
		// wrong.
		assertion.setGroups(new java.util.LinkedHashSet<>(Set.of("component-centric-validation")));
		return assertion;
	}

	/** A corpus held the way the DuckDB engine holds it: immutable. */
	private static AssertionService duckLikeCorpus(List<Assertion> corpus) {
		AssertionService service = mock(AssertionService.class);
		when(service.findAll()).thenReturn(List.copyOf(corpus));
		AssertionGroup group = new AssertionGroup();
		group.setName("component-centric-validation");
		group.setAssertions(Set.copyOf(corpus));
		when(service.getAllAssertionGroups()).thenReturn(List.of(group));
		return service;
	}

	private static AssertionController controllerFor(AssertionService assertions,
			List<Assertion> droolsRules) {
		DroolsRulesValidationService drools = mock(DroolsRulesValidationService.class);
		when(drools.getAssertions()).thenReturn(droolsRules);
		TraceabilityComparisonService traceability = mock(TraceabilityComparisonService.class);
		when(traceability.getAssertions()).thenReturn(List.of());
		return new AssertionController(assertions, mock(AssertionLookup.class), drools,
				traceability, new ObjectProvider<>() {
					@Override
					public DuckAssertionService getObject(Object... args) {
						return null;
					}

					@Override
					public DuckAssertionService getObject() {
						return null;
					}

					@Override
					public DuckAssertionService getIfAvailable() {
						return null;
					}

					@Override
					public DuckAssertionService getIfUnique() {
						return null;
					}
				});
	}

	@Test
	void droolsRulesCanBeIncludedWithoutWritingToTheCorpus() {
		List<Assertion> corpus = List.of(
				assertion("first", "component-centric-validation"),
				assertion("second", "component-centric-validation"));
		AssertionService service = duckLikeCorpus(corpus);
		AssertionController controller = controllerFor(service,
				List.of(assertion("a Drools rule", "drools")));

		// Before the fix this threw UnsupportedOperationException from
		// ArrayList$Itr... via List.copyOf's immutable list, i.e. HTTP 500.
		List<Assertion> listed = controller.getAssertions(true, false, false, false);

		assertEquals(3, listed.size(), "two corpus assertions plus the rule");
		assertEquals(2, service.findAll().size(), "and the corpus is untouched");
	}

	@Test
	void traceabilityAndSepAssertionsToo() {
		// Same shape, different flags: all three additions hit the same list.
		List<Assertion> corpus = List.of(assertion("first", "component-centric-validation"));
		AssertionService service = duckLikeCorpus(corpus);
		AssertionController controller = controllerFor(service, List.of());

		List<Assertion> listed = controller.getAssertions(false, true, true, false);
		assertTrue(listed.size() >= 1, "SEP assertions are appended, not refused");
		assertEquals(1, service.findAll().size());
	}

	@Test
	void theResourceFilterStillWorksAndStillDoesNotWriteBack() {
		// ignoreResourceType=true already produced a private list via a stream
		// collector, which is why the bug was intermittent: it depended on a
		// flag that has nothing to do with the addition.
		List<Assertion> corpus = List.of(
				assertion("a resource", "resource"),
				assertion("real", "component-centric-validation"));
		AssertionService service = duckLikeCorpus(corpus);
		AssertionController controller = controllerFor(service,
				List.of(assertion("a Drools rule", "drools")));

		List<Assertion> listed = controller.getAssertions(true, false, false, true);

		assertEquals(2, listed.size(), "the resource assertion is filtered, the rule is added");
		assertTrue(listed.stream().noneMatch(a -> "resource".equals(a.getKeywords())));
		assertEquals(2, service.findAll().size());
	}

	@Test
	void groupsAreStillJoinedOntoTheResponse() {
		// The join is why getAssertionsAndJoinGroups exists: the MySQL path
		// needs it, because its assertions come back from the database without
		// group membership. Copying the list must not lose that.
		List<Assertion> corpus = List.of(assertion("first", "component-centric-validation"));
		AssertionController controller = controllerFor(duckLikeCorpus(corpus), List.of());

		List<Assertion> listed = controller.getAssertions(false, false, false, false);

		assertEquals(1, listed.size());
		assertTrue(listed.get(0).getGroups().contains("component-centric-validation"),
				"groups: " + listed.get(0).getGroups());
	}
}
