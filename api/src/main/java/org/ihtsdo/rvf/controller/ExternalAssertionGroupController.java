package org.ihtsdo.rvf.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.ihtsdo.rvf.entity.Assertion;
import org.ihtsdo.rvf.entity.AssertionGroup;
import org.ihtsdo.rvf.helper.AssertionHelper;
import org.ihtsdo.rvf.model.AssertionGroupConfiguration;
import org.ihtsdo.rvf.service.AssertionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/external/groups")
@Api(tags = "External Assertions Groups", description = "-")
public class ExternalAssertionGroupController {

	@Autowired
	private AssertionService assertionService;
	
	@Autowired
	private AssertionHelper assertionHelper;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final Logger logger = LoggerFactory.getLogger(ExternalAssertionGroupController.class);

	@RequestMapping(value = "", method = RequestMethod.GET)
	@ResponseBody
	@ResponseStatus(HttpStatus.OK)
	@ApiOperation(value = "Get all assertion groups", notes = "Retrieves all assertion groups defined in the system.")
	public List<AssertionGroup> getGroups() {
		List<AssertionGroup> result = assertionService.getAllAssertionGroups();
		if (result == null) {
			result = new ArrayList<>();
		}
		return result;
	}


	@RequestMapping(value = "{name}/configurations", method = RequestMethod.GET)
	@ResponseBody
	@ResponseStatus(HttpStatus.OK)
	@ApiOperation(value = "Get an assertion group", notes = "Retrieves an assertion group configurations for a given name")
	public AssertionGroupConfiguration getAssertionGroupConfigurations(@PathVariable final String name) throws IOException {
		return assertionService.getAssertionGroupConfigurationByName(name);
	}

	@RequestMapping(value = "{name}", method = RequestMethod.GET)
	@ResponseBody
	@ResponseStatus(HttpStatus.OK)
	@ApiOperation(value = "Get an assertion group", notes = "Retrieves an assertion group configurations for a given name")
	public ExternalAssertionGroupDTO getAssertionGroup(@PathVariable final String name) throws IOException {
		AssertionGroup assertionGroup =  assertionService.getAssertionGroupByName(name);
		ExternalAssertionGroupDTO groupDTO = new ExternalAssertionGroupDTO();
		groupDTO.setName(assertionGroup.getName());
		groupDTO.setAssertions(assertionGroup.getAssertions());
		return groupDTO;
	}

	class ExternalAssertionGroupDTO {
		private String name;
		private int total = 0;
		private Set<Assertion> assertions;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public int getTotal() {
			if (assertions != null) {
				total = assertions.size();
			}
			return total;
		}

		public Set<Assertion> getAssertions() {
			return assertions;
		}

		public void setAssertions(Set<Assertion> assertions) {
			this.assertions = assertions;
		}
	}

}
