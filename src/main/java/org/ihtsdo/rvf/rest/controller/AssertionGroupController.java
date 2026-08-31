package org.ihtsdo.rvf.rest.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.ihtsdo.rvf.core.data.model.AssertionGroup;
import org.ihtsdo.rvf.core.service.AssertionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Read access to the assertion groups.
 *
 * <p>Only the whole-collection listing is here, and that is not an arbitrary
 * line. {@code getAllAssertionGroups} goes through {@link AssertionService},
 * which both engines implement, whereas every other group endpoint resolves a
 * group by its numeric database id through {@code AssertionGroupRepository} - a
 * JPA repository, and a numeric id the published DuckDB store does not have.
 * Those endpoints are in {@link AssertionGroupAdministrationController} and stay
 * MySQL-only.
 *
 * <p>This listing is what {@code validation-framework-browser-ui} calls to
 * populate its group selector, and it reads {@code name} only.
 */
@RestController
@RequestMapping("/groups")
@Tag(name = "Assertions Groups")
public class AssertionGroupController {

	private final AssertionService assertionService;

	@Autowired
	public AssertionGroupController(AssertionService assertionService) {
		this.assertionService = assertionService;
	}

	@RequestMapping(value = "", method = RequestMethod.GET)
	@ResponseBody
	@ResponseStatus(HttpStatus.OK)
	@Operation(summary = "Get all assertion groups", description = "Retrieves all assertion groups defined in the system.")
	public List<AssertionGroup> getGroups() {
		List<AssertionGroup> result = assertionService.getAllAssertionGroups();
		if (result == null) {
			result = new ArrayList<>();
		}
		return result;
	}
}
