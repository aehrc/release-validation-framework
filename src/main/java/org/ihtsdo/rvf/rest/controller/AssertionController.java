package org.ihtsdo.rvf.rest.controller;

import org.ihtsdo.rvf.core.service.duck.DuckAssertionService;
import org.ihtsdo.rvf.core.service.duck.DuckStorePacks;
import org.springframework.beans.factory.ObjectProvider;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

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

	/**
	 * {@code ObjectProvider} for the DuckDB service because it only exists when
	 * {@code rvf.execution.engine=duckdb}. Injecting it directly would make this
	 * controller - and every endpoint on it - unloadable on the MySQL engine.
	 */
	private final ObjectProvider<DuckAssertionService> duckAssertions;

	@Autowired
	public AssertionController(AssertionService assertionService,
			AssertionLookup assertionLookup,
			DroolsRulesValidationService droolsValidationService,
			TraceabilityComparisonService traceabilityComparisonService,
			ObjectProvider<DuckAssertionService> duckAssertions) {
		this.assertionService = assertionService;
		this.assertionLookup = assertionLookup;
		this.droolsValidationService = droolsValidationService;
		this.traceabilityComparisonService = traceabilityComparisonService;
		this.duckAssertions = duckAssertions;
	}

	/**
	 * What an assertion actually runs, for showing beside its failures.
	 *
	 * <p>A report names an assertion and counts its failures; it cannot say what
	 * the assertion checked. The transpiled statements are already in the store
	 * the engine executes, so this reads them from there rather than from the
	 * corpus - what is returned is what RAN, which is the only version worth
	 * showing next to a failure.
	 *
	 * <p>404 for a Drools rule or an MRCM check, with a reason: those are not
	 * SQL and have no statements. Saying "not found" without that reads as an
	 * unknown assertion.
	 */
	@GetMapping(value = "{uuid}/source")
	@Operation(summary = "The source an assertion executes: its file and transpiled statements.",
			description = "SQL assertions only. Drools rules and MRCM checks are not SQL and return 404 "
					+ "with an explanation. The statements are read from the precompiled store, so they "
					+ "are what actually ran rather than what the corpus currently holds.")
	public ResponseEntity<Map<String, Object>> getAssertionSource(
			@Parameter(description = "The assertion uuid") @PathVariable final String uuid) {

		DuckAssertionService duck = duckAssertions.getIfAvailable();
		if (duck == null) {
			return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
					.body(Map.of("uuid", uuid, "message",
							"Assertion source is only available on the DuckDB execution engine."));
		}
		return duck.storedAssertion(uuid)
				.<ResponseEntity<Map<String, Object>>>map(stored -> {
					Map<String, Object> body = new LinkedHashMap<>();
					body.put("uuid", stored.uuid());
					body.put("type", "SQL");
					body.put("file", stored.file());
					body.put("text", stored.text());
					body.put("keywords", stored.keywords());
					body.put("severity", stored.severity());
					body.put("statements", stored.statements());
					return ResponseEntity.ok(body);
				})
				.orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
						.body(Map.of("uuid", uuid, "message",
								"No SQL source for this assertion. Drools rules and MRCM checks are "
										+ "not SQL; their logic lives in the rule set and the MRCM "
										+ "library rather than in a script.")));
	}

	/**
	 * What the loaded assertion corpus is made of.
	 *
	 * <p>The provenance packs cost. Baked into the image, the tag named the
	 * corpus; fetched at runtime, this is the only thing that can answer which
	 * assertions produced a report.
	 */
	@GetMapping(value = "packs")
	@Operation(summary = "The assertion packs the loaded corpus was assembled from.",
			description = "Name, version, digest and assertion count per pack. DuckDB engine only.")
	public ResponseEntity<Map<String, Object>> getPacks() {
		DuckAssertionService duck = duckAssertions.getIfAvailable();
		if (duck == null) {
			return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("message",
					"Assertion packs are only available on the DuckDB execution engine."));
		}
		List<Map<String, Object>> packs = duck.loadedPacks().stream()
				.map(pack -> {
					Map<String, Object> entry = new LinkedHashMap<>();
					entry.put("name", pack.name());
					entry.put("version", pack.version());
					entry.put("digest", pack.digest());
					entry.put("assertions", pack.store().assertions().size());
					return entry;
				})
				.toList();
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("assertions", duck.findAll().size());
		body.put("packs", packs);
		return ResponseEntity.ok(body);
	}

	/**
	 * Fetches the configured packs and swaps the corpus, or changes nothing.
	 *
	 * <p>Every pack is fetched and digest-checked, then merged, then the source
	 * is built - and only then is anything published. A failure at any step
	 * leaves the running corpus exactly as it was and says why, because the
	 * alternative is an engine serving a half-applied assertion set, which
	 * reports a release as clean for assertions it no longer holds.
	 *
	 * <p>409 for a merge conflict specifically: that is not a server fault but
	 * two packs that cannot be combined, and the body lists every conflict so a
	 * publishing pipeline can fix them in one pass.
	 */
	@PostMapping(value = "packs/refresh")
	@Operation(summary = "Re-fetch the configured assertion packs and swap the corpus atomically.",
			description = "Digests are verified before anything is loaded. On any failure the "
					+ "current corpus keeps serving. DuckDB engine only.")
	public ResponseEntity<Map<String, Object>> refreshPacks() {
		DuckAssertionService duck = duckAssertions.getIfAvailable();
		if (duck == null) {
			return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of("message",
					"Assertion packs are only available on the DuckDB execution engine."));
		}
		try {
			return ResponseEntity.ok(Map.of("loaded", duck.refreshConfiguredPacks()));
		} catch (DuckStorePacks.ConflictException e) {
			return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
					"message", "the configured packs cannot be merged; the corpus is unchanged",
					"conflicts", e.getConflicts()));
		} catch (IOException e) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of(
					"message", "could not load the configured packs; the corpus is unchanged",
					"reason", String.valueOf(e.getMessage())));
		}
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
