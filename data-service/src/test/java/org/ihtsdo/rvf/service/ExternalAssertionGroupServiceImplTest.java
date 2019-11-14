package org.ihtsdo.rvf.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.ihtsdo.otf.resourcemanager.ResourceManager;
import org.ihtsdo.rvf.entity.Assertion;
import org.ihtsdo.rvf.model.AssertionGroupConfiguration;
import org.ihtsdo.rvf.repository.AssertionRepository;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

@RunWith(SpringRunner.class)
@ContextConfiguration(classes = {DataServiceTestConfig.class})
public class ExternalAssertionGroupServiceImplTest {

    @MockBean
    private AssertionRepository assertionRepositoryMock;

    @MockBean
    private ResourceManager resourceManager;

    @Autowired
    private ExternalAssertionGroupService externalAssertionGroupService;

    private List<Assertion> assertionsTestList = new ArrayList<>();

    private ObjectMapper objectMapper = new ObjectMapper();

    private static final String FILE_TEST_ASSERTION_JSON = "test-assertions.json";

    @Before
    public void setup() throws IOException {
        InputStream resourceStream = getClass().getClassLoader().getResourceAsStream(FILE_TEST_ASSERTION_JSON);
        TestAssertions testAssertions = objectMapper.readValue(resourceStream, TestAssertions.class);
        assertionsTestList = testAssertions.getAssertions();
        when(assertionRepositoryMock.findAll()).thenReturn(assertionsTestList);
    }

    @Test
    public void testIncludesAssertionsWithOnlyUUIDsList() throws IOException {
        String group = "case-include-by-uuids";
        mockAssertionGroupConfiguration(group);
        
        Set<Assertion> assertionSet = externalAssertionGroupService.loadAssertionsByAssertionGroupName(group);
        assertEquals(3, assertionSet.size());
        
        mockAssertionGroupConfiguration(group);
        AssertionGroupConfiguration configuration = externalAssertionGroupService.loadConfigurationsByAssertionGroupName(group);
        Set<String> uuidsInConfiguration = configuration.getAssertions().getIncludes().getUuids();
        Set<String> uuidsInAssertion = assertionSet.stream().map(assertion -> assertion.getUuid().toString()).collect(Collectors.toSet());
        assertTrue("Number of assertions in the group should match the number of UUIDs configured in configurations",
                uuidsInAssertion.size() == uuidsInConfiguration.size());

        uuidsInConfiguration.removeAll(uuidsInAssertion);
        assertEquals("UUIDs of the assertions in the group should be exactly matched with the configurations",
                0, uuidsInConfiguration.size());

    }

    @Test
    public void testIncludesAssertionsWithOnlyCategoryList() throws IOException {
        String group = "case-include-by-categories";
        mockAssertionGroupConfiguration(group);

        Set<Assertion> assertionSet = externalAssertionGroupService.loadAssertionsByAssertionGroupName(group);
        assertEquals(4, assertionSet.size());

        mockAssertionGroupConfiguration(group);
        AssertionGroupConfiguration configuration = externalAssertionGroupService.loadConfigurationsByAssertionGroupName(group);
        Set<String> categoriesInConfigurations = configuration.getAssertions().getIncludes().getCategories();

        for (Assertion assertion : assertionSet) {
            Set<String> categoriesInAssertions = new HashSet<>();
            Collections.addAll(categoriesInAssertions, assertion.getKeywords().split(","));
            assertFalse("Assertion " + assertion.getUuid() + " must have at least 1 category specified in the assertion group configuration",
                    Collections.disjoint(categoriesInConfigurations, categoriesInAssertions));
        }
        
    }

    @Test
    public void testIncludesAssertionsWithOnlyTexts() throws IOException {
        String group = "case-include-by-texts";
        mockAssertionGroupConfiguration(group);

        Set<Assertion> assertionSet = externalAssertionGroupService.loadAssertionsByAssertionGroupName(group);
        assertEquals(2, assertionSet.size());

        mockAssertionGroupConfiguration(group);
        AssertionGroupConfiguration configuration = externalAssertionGroupService.loadConfigurationsByAssertionGroupName(group);
        Set<String> textsInConfigurations = configuration.getAssertions().getIncludes().getTexts();

        for (Assertion assertion : assertionSet) {
            String textInAssertion = assertion.getAssertionText();
            assertTrue("Assertion text of assertion " + assertion.getUuid() + " must contains 1 of the texts specified in configurations",
                    textsInConfigurations.stream().anyMatch(textInAssertion::contains));
        }
    }

    @Test
    public void testIncludesAssertionsWithOnlyGroups() throws IOException {
        String group = "case-include-by-groups";
        mockAssertionGroupConfiguration(group);

        String subgroup1 = "case-include-by-uuid";
        mockAssertionGroupConfiguration(subgroup1);

        String subgroup2 = "case-include-by-categories";
        mockAssertionGroupConfiguration(subgroup2);

        Set<Assertion> assertionSet = externalAssertionGroupService.loadAssertionsByAssertionGroupName(group);
        assertEquals(4, assertionSet.size());

    }

    @Test
    public void testExcludeAssertionsConfigurations() throws IOException {
        String group = "case-exclude";
        mockAssertionGroupConfiguration(group);

        Set<Assertion> assertionSet = externalAssertionGroupService.loadAssertionsByAssertionGroupName(group);
        assertEquals(3, assertionSet.size());
    }

    private void mockAssertionGroupConfiguration(String groupName) throws IOException {
        String path = ExternalAssertionGroupServiceImpl.ASSERTION_GROUPS + groupName + ExternalAssertionGroupServiceImpl.JSON_FILE_EXT;
        InputStream assertionGroupConfigStream = getClass().getClassLoader().getResourceAsStream(path);
        doReturn(assertionGroupConfigStream).when(resourceManager).readResourceStreamOrNullIfNotExists(path);
    }
    


    public static class TestAssertions {

        List<Assertion> assertions;

        public List<Assertion> getAssertions() {
            return assertions;
        }

        public void setAssertions(List<Assertion> assertions) {
            this.assertions = assertions;
        }
    }





}
