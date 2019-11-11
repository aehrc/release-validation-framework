package org.ihtsdo.rvf.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssertionGroupConfiguration {

    private String name;
    private String description;
    private AssertionsConfiguration assertions;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public AssertionsConfiguration getAssertions() {
        return assertions;
    }

    public void setAssertions(AssertionsConfiguration assertions) {
        this.assertions = assertions;
    }
}
