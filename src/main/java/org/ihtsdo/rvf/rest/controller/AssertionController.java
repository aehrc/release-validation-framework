package org.ihtsdo.rvf.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.ihtsdo.rvf.core.data.model.Assertion;
import org.ihtsdo.rvf.core.data.model.AssertionGroup;
import org.ihtsdo.rvf.core.data.model.Test;
import org.ihtsdo.rvf.core.service.AssertionService;
import org.ihtsdo.rvf.core.service.DroolsRulesValidationService;
import org.ihtsdo.rvf.core.service.TraceabilityComparisonService;
import org.ihtsdo.rvf.rest.helper.AssertionLookup;
import org.snomed.quality.validator.mrcm.SEPRefsetValidationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Read access to the assertion catalogue.
 *
 * <p>Engine-agnostic on purpose. Everything here goes through
 * {@link AssertionService}, which has an implementation for both engines - JPA
 * over MySQL, and {@code DuckAssertionService} over the published store - so the
 * catalogue is browsable in either mode. That is what
 * {@code validation-framework-browser-ui} consumes: it calls {@code /assertions}
 * and {@code /groups} and reads only {@code uuid}, {@code groups},
 * {@code assertionText} and {@code keywords}, none of which need a database.
 *
 * <p>The endpoints that WRITE to the catalogue, and the one that executes a
 * single assertion against loaded MySQL release data, live in
 * {@link AssertionAdministrationController} and remain MySQL-only. Splitting on
 * that line rather than gating the whole controller is what lets the browser UI
 * work against DuckDB without pretending the store is writable.
 *
 * <p>One consequence worth knowing: in DuckDB mode a numeric {@code {id}}
 * resolves to nothing, so {@code GET /assertions/{numericId}} answers 404 while
 * {@code GET /assertions/{uuid}} answers normally. See {@link AssertionLookup}.
 */
@RestController
@RequestMapping("/assertions")
@Tag(name = "Assertions")
public class AssertionController {

	private final AssertionService assertionService;
	private final AssertionLookup assertionLookup;
	private final DroolsRulesValidationService droolsValidationService;
	private final TraceabilityComparisonService traceabilityComparisonService;

	@Autowired
	public AssertionController(AssertionService assertionService,
			AssertionLookup assertionLookup,
			DroolsRulesValidationService droolsValidationService,
			TraceabilityComparisonService traceabilityComparisonService) {
		this.assertionService = assertionService;
		this.assertionLookup = assertionLookup;
		this.droolsValidationService = droolsValidationService;
		this.traceabilityComparisonService = traceabilityComparisonService;
	}

	@RequestMapping(value = "", method = RequestMethod.GET)
	@ResponseBody
	@ResponseStatus(HttpStatus.OK)
	@Operation(summary = "Get all assertions", description = "Retrieves all assertions available in the system.")
	public List<Assertion> getAssertions(@RequestParam(required = false) final boolean includeDroolsRules,
										 @RequestParam(required = false) final boolean includeTraceabilityAssertions,
										 @RequestParam(required = false) final boolean includeSEPAssertions,
										 @RequestParam(required = false) final boolean ignoreResourceType) {
		List<Assertion> assertions = getAssertionsAndJoinGroups();
		if (ignoreResourceType) {
			assertions = assertions.stream().filter(assertion -> !assertion.getKeywords().equals("resource")).collect(Collectors.toList());
		}
		if (includeDroolsRules) {
			assertions.addAll(droolsValidationService.getAssertions());
		}
		if (includeTraceabilityAssertions) {
			assertions.addAll(traceabilityComparisonService.getAssertions());
		}
		if (includeSEPAssertions) {
			SEPRefsetValidationService sepRefsetValidationService = new SEPRefsetValidationService();
			assertions.addAll(sepRefsetValidationService.getAssertions().stream()
					.map(AssertionController::toAssertion)
					.toList());
		}

		return assertions;
	}

	@RequestMapping(value = "{id}/tests", method = RequestMethod.GET)
	@ResponseBody
	@ResponseStatus(HttpStatus.OK)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "400", description = "Invalid ID supplied."),
			@ApiResponse(responseCode = "404", description = "Assertion tests not found.") })
	@Operation(summary = "Retrieves all tests for an assertion", description = "Retrieves all tests which belong to a given assertion id.")
	public List<Test> getTestsForAssertion(@PathVariable final String id) {
		final Assertion assertion = assertionLookup.find(id);

		return assertionService.getTestsByAssertionId(assertion.getAssertionId());
	}

	@RequestMapping(value = "{id}", method = RequestMethod.GET)
	@ApiResponses(value = {
			@ApiResponse(responseCode = "400", description = "Invalid ID supplied"),
			@ApiResponse(responseCode = "404", description = "Assertion not found") })
	@Operation(summary = "Get an assertion", description = "Retrieves an assertion identified by the id.")
	public ResponseEntity<Assertion> getAssertion(
			@Parameter(description = "Assertion id or uuid", required = true) @PathVariable final String id) {
		Assertion assertion = null;
		try {
			assertion = assertionLookup.find(id);
		} catch (IllegalArgumentException e) {
			return new ResponseEntity<>((Assertion) null, HttpStatus.BAD_REQUEST);
		}
		if (assertion == null) {
			return new ResponseEntity<>(HttpStatus.NOT_FOUND);
		}
		return new ResponseEntity<>(assertion, HttpStatus.OK);
	}

	private List<Assertion> getAssertionsAndJoinGroups() {
		List<Assertion> assertions = assertionService.findAll();
		List<AssertionGroup> assertionGroups = assertionService.getAllAssertionGroups();
		assertionGroups.forEach(assertionGroup -> assertionGroup.getAssertions().forEach(a -> assertions.forEach(b -> {
			if (a.getUuid().toString().equals(b.getUuid().toString())) {
				b.addGroup(assertionGroup.getName());
			}
		})));
		return assertions;
	}

	public static Assertion toAssertion(org.snomed.quality.validator.mrcm.Assertion mrcmAssertion) {
		Assertion assertion = new Assertion();
		assertion.setUuid(UUID.fromString(mrcmAssertion.getUuid().toString()));
		assertion.setAssertionText(mrcmAssertion.getAssertionText());
		assertion.setType("SEP");
		return assertion;
	}
}
