package org.ihtsdo.rvf.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang.StringUtils;
import org.ihtsdo.otf.resourcemanager.ResourceManager;
import org.ihtsdo.rvf.entity.Assertion;
import org.ihtsdo.rvf.entity.AssertionGroup;
import org.ihtsdo.rvf.model.AssertionGroupConfiguration;
import org.ihtsdo.rvf.model.AssertionsConfiguration;
import org.ihtsdo.rvf.repository.AssertionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Transactional
public class ExternalAssertionGroupServiceImpl implements ExternalAssertionGroupService {

    @Autowired
    private AssertionRepository assertionRepo;

    @Autowired
    private ResourceManager assertionResourceManager;

    private ObjectMapper objectMapper = new ObjectMapper();

    public static final String ASSERTION_GROUPS = "assertion-groups/";
    public static final String JSON_FILE_EXT = ".json";

    private static final String EXCLUDES = "excludes";
    private static final String INCLUDES = "includes";

    @Override
    public AssertionGroup findByName(String groupName) {
        try {
            InputStream inputStream = assertionResourceManager.readResourceStreamOrNullIfNotExists(ASSERTION_GROUPS + groupName + JSON_FILE_EXT);
            if (inputStream != null) {
                AssertionGroup assertionGroup = new AssertionGroup();
                assertionGroup.setName(groupName);
                assertionGroup.setAssertions(loadAssertionsByAssertionGroupName(groupName));
                return assertionGroup;
            }

        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public AssertionGroupConfiguration loadConfigurationsByAssertionGroupName(String name) throws IOException {
        String fileName = ASSERTION_GROUPS + name + JSON_FILE_EXT;
        InputStream inputStream = assertionResourceManager.readResourceStreamOrNullIfNotExists(fileName);
        if (inputStream != null) {
            AssertionGroupConfiguration assertionGroupConfiguration = objectMapper.readValue(inputStream, AssertionGroupConfiguration.class);
            return assertionGroupConfiguration;
        }
        return null;
    }

    @Override
    public Set<Assertion> loadAssertionsByAssertionGroupName(String groupName) {
        Set<Assertion> assertions = new HashSet<>();
        Set<String> includedUUIDs = new HashSet<>();
        Set<String> excludedUUIDs = new HashSet<>();
        List<Assertion> allAssertions = assertionRepo.findAll();
        ObjectMapper objectMapper = new ObjectMapper();
        String pathToConfig = ASSERTION_GROUPS + groupName + JSON_FILE_EXT;
        try {
            InputStream inputStream = assertionResourceManager.readResourceStreamOrNullIfNotExists(pathToConfig);
            AssertionGroupConfiguration assertionGroupConfiguration = objectMapper.readValue(inputStream, AssertionGroupConfiguration.class);
            if (assertionGroupConfiguration != null && assertionGroupConfiguration.getAssertions() != null) {
                if (assertionGroupConfiguration.getAssertions().getIncludes() != null) {
                    includedUUIDs.addAll(getAssertionUUIDsInAssertionsConfig(groupName, assertionGroupConfiguration.getAssertions().getIncludes(), allAssertions, INCLUDES));
                }
                if (assertionGroupConfiguration.getAssertions().getExcludes() != null) {
                    excludedUUIDs.addAll(getAssertionUUIDsInAssertionsConfig(groupName, assertionGroupConfiguration.getAssertions().getExcludes(), allAssertions, EXCLUDES));
                }
                includedUUIDs.removeAll(excludedUUIDs);
                assertions = allAssertions.stream().filter(assertion -> includedUUIDs.contains(assertion.getUuid().toString())).collect(Collectors.toSet());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return assertions;
    }

    private void findMatchingAssertionUUIDsFromConfiguration(AssertionsConfiguration.InclusionExclusionConfiguration configuration, Set<String> uuids, List<Assertion> allAssertions) {
        if (configuration.getUuids() != null) {
            uuids.addAll(configuration.getUuids());
        }
        if (configuration.getCategories() != null) {
            for (Assertion assertion : allAssertions) {
                for (String category : configuration.getCategories()) {
                    String[] keywords = assertion.getKeywords().split(",");
                    for (String keyword : keywords) {
                        if (StringUtils.equals(category, keyword)) {
                            uuids.add(assertion.getUuid().toString());
                            break;
                        }
                    }

                }
            }
        }
        if (configuration.getTexts() != null) {
            for (Assertion assertion : allAssertions) {
                for (String keyword : configuration.getTexts()) {
                    if (StringUtils.isBlank(keyword.trim())) {
                        break;
                    }
                    if (StringUtils.containsIgnoreCase(assertion.getAssertionText(), keyword)) {
                        uuids.add(assertion.getUuid().toString());
                    }
                }
            }
        }
    }

    private Set<String> getAssertionUUIDsInAssertionsConfig(String groupName, AssertionsConfiguration.InclusionExclusionConfiguration configuration
            , List<Assertion> allAssertions, String field) throws IOException {
        Set<String> uuids = new HashSet<>();
        findMatchingAssertionUUIDsFromConfiguration(configuration, uuids, allAssertions);
        if (configuration.getGroups() != null) {
            for (String group : configuration.getGroups()) {
                loadAssertionUUIDsFromGroupConfig(groupName, allAssertions, uuids, group, field, new ArrayList<>());
            }
        }
        return uuids;
    }

    private void loadAssertionUUIDsFromGroupConfig(String originGroup, List<Assertion> allAssertions, Set<String> uuids, String referencedGroup, String field, List<String> referencedGroupChain) throws IOException {
        referencedGroupChain.add(referencedGroup);
        if (originGroup.equals(referencedGroup)) {
            String referencedGroupChainAsString = StringUtils.join(referencedGroupChain, " -> ");
            throw new IllegalArgumentException("Cycling dependency on assertion group detected for: " + referencedGroupChainAsString);
        }
        String pathToConfig = ASSERTION_GROUPS + referencedGroup + JSON_FILE_EXT;
        InputStream inputStream = assertionResourceManager.readResourceStreamOrNullIfNotExists(pathToConfig);
        if (inputStream != null) {
            ObjectMapper objectMapper = new ObjectMapper();
            AssertionGroupConfiguration assertionGroupConfiguration = objectMapper.readValue(inputStream, AssertionGroupConfiguration.class);
            AssertionsConfiguration assertionsConfiguration = assertionGroupConfiguration.getAssertions();
            AssertionsConfiguration.InclusionExclusionConfiguration configuration = null;
            if (INCLUDES.equals(field)) {
                configuration = assertionsConfiguration.getIncludes();
            } else if (EXCLUDES.equals(field)) {
                configuration = assertionsConfiguration.getExcludes();
            }
            if (configuration != null) {
                findMatchingAssertionUUIDsFromConfiguration(configuration, uuids, allAssertions);
                if (configuration.getGroups() != null) {
                    for (String group : configuration.getGroups()) {
                        loadAssertionUUIDsFromGroupConfig(originGroup, allAssertions, uuids, group, field, referencedGroupChain);
                    }
                }
            }
        }


    }
}
