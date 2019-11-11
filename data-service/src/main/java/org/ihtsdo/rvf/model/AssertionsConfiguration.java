package org.ihtsdo.rvf.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssertionsConfiguration {

    private InclusionExclusionConfiguration includes;
    private InclusionExclusionConfiguration excludes;

    public AssertionsConfiguration() {
    }

    public AssertionsConfiguration(InclusionExclusionConfiguration includes, InclusionExclusionConfiguration excludes) {
        this.includes = includes;
        this.excludes = excludes;
    }

    public InclusionExclusionConfiguration getIncludes() {
        return includes;
    }

    public void setIncludes(InclusionExclusionConfiguration includes) {
        this.includes = includes;
    }

    public InclusionExclusionConfiguration getExcludes() {
        return excludes;
    }

    public void setExcludes(InclusionExclusionConfiguration excludes) {
        this.excludes = excludes;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class InclusionExclusionConfiguration {
        private Set<String> uuids;
        private Set<String> categories;
        private Set<String> groups;

        public Set<String> getUuids() {
            return uuids;
        }

        public void setUuids(Set<String> uuids) {
            this.uuids = uuids;
        }

        public Set<String> getCategories() {
            return categories;
        }

        public void setCategories(Set<String> categories) {
            this.categories = categories;
        }

        public Set<String> getGroups() {
            return groups;
        }

        public void setGroups(Set<String> groups) {
            this.groups = groups;
        }
    }

}
