package org.ihtsdo.rvf.core.service;

import org.ihtsdo.otf.rest.exception.BusinessServiceException;
import org.ihtsdo.otf.utils.ZipFileUtils;
import org.ihtsdo.rvf.configuration.IntegrationTest;
import org.ihtsdo.rvf.core.data.model.Assertion;
import org.ihtsdo.rvf.core.data.model.TestRunItem;
import org.ihtsdo.rvf.core.service.config.MysqlExecutionConfig;
import org.ihtsdo.rvf.core.service.whitelist.WhitelistItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class RVFAssertionsWhitelistIntegrationTest extends IntegrationTest {
    private static final String COMPONENT_CENTRIC_VALIDATION = "component-centric-validation";
    private static final String PROSPECTIVE_RELEASE = "rvf_regression_test_prospective";
    private static final String PREVIOUS_RELEASE = "rvf_regression_test_previous";

    @Autowired
    private AssertionExecutionService assertionExecutionService;

    @Autowired
    private AssertionService assertionService;

    @Autowired
    private ReleaseDataManager releaseDataManager;

    @Autowired
    private ResourceDataLoader resourceDataLoader;

    // @MockBean, not @Mock + @InjectMocks: the extractor is a Spring bean, and
    // injecting into an @Autowired field sets it on whatever reference the test
    // holds rather than on the instance the container hands to the code under
    // test - the mock was created, the stubbing was recorded, and the real
    // service answered anyway, which reads as "whitelisting does not work".
    @MockBean
    private WhitelistService whitelistService;

    @Autowired
    private MysqlFailuresExtractor mysqlFailuresExtractor;

    private MysqlExecutionConfig config;
    private final List<String> rf2FilesLoaded = new ArrayList<>();
    private boolean isRunFirstTime = true;

    @BeforeEach
    public void setUp() throws IOException, BusinessServiceException {
        if (!isRunFirstTime) {
            return;
        }
        //load previous and prospective versions if not loaded already
        assertNotNull(releaseDataManager);
        config = new MysqlExecutionConfig(System.currentTimeMillis());
        config.setPreviousVersion(PREVIOUS_RELEASE);
        config.setProspectiveVersion(PROSPECTIVE_RELEASE);
        config.setFailureExportMax(10);
        if (!releaseDataManager.isKnownRelease(PREVIOUS_RELEASE)) {
            URL previousReleaseUrl = RVFAssertionsWhitelistIntegrationTest.class.getResource("/SnomedCT_RegressionTest_20130131");
            assertNotNull(previousReleaseUrl, "Must not be null");
            File previousFile = new File(previousReleaseUrl.getFile() + "_test.zip");
            ZipFileUtils.zip(previousReleaseUrl.getFile(), previousFile.getAbsolutePath());
            releaseDataManager.uploadPublishedReleaseData(previousFile, "regression_test", "previous", Collections.emptyList());
        }
        if (!releaseDataManager.isKnownRelease(PROSPECTIVE_RELEASE)) {
            final URL prospectiveReleaseUrl = RVFAssertionsWhitelistIntegrationTest.class.getResource("/SnomedCT_RegressionTest_20130731");
            assertNotNull(prospectiveReleaseUrl, "Must not be null");
            final File prospectiveFile = new File(prospectiveReleaseUrl.getFile() + "_test.zip");
            ZipFileUtils.zip(prospectiveReleaseUrl.getFile(), prospectiveFile.getAbsolutePath());
            releaseDataManager.loadSnomedData(PROSPECTIVE_RELEASE, rf2FilesLoaded, Collections.emptyList(), prospectiveFile);
            resourceDataLoader.loadResourceData(PROSPECTIVE_RELEASE);
            List<Assertion> assertions = assertionService.getAssertionsByKeyWords("resource", true);
            assertNotNull(assertions);
            assertFalse(assertions.isEmpty());
            assertionExecutionService.executeAssertions(assertions, config);
        }
        isRunFirstTime = false;
    }

    @Test
    public void testWhitelistedAssertions() throws Exception {
        final List<Assertion> assertions = assertionService.getAssertionsByKeyWords(COMPONENT_CENTRIC_VALIDATION, false);
        List<TestRunItem> testRunItems = runAssertionsTest(assertions);
        assertNotNull(assertions);

        // assert results without validating against whitelisting items
        when(whitelistService.isWhitelistDisabled()).thenReturn(true);
        mysqlFailuresExtractor.extractTestResults(testRunItems, config, assertions);
        for (TestRunItem test : testRunItems) {
            if ("31f5e2c8-b0b9-42ee-a9bf-87d95edad83b".equals(test.getAssertionUuid().toString())) {
                assertEquals(2L, test.getFailureCount().longValue());
            }
        }

        // assert results with validating against whitelisting items
        when(whitelistService.isWhitelistDisabled()).thenReturn(false);
        List<WhitelistItem> whitelistItems = Collections.singletonList(new WhitelistItem("31f5e2c8-b0b9-42ee-a9bf-87d95edad83b", "3008913022", "703672002", "1,900000000000207008,703860006,en,900000000000003001,Toxic effect of antimony and/or its compounds (disorder),900000000000020002"));

        // mock the whitelist items
        when(whitelistService.checkComponentFailuresAgainstWhitelist(any())).thenReturn(whitelistItems);
        mysqlFailuresExtractor.extractTestResults(testRunItems, config, assertions);
        for (TestRunItem test : testRunItems) {
            if ("31f5e2c8-b0b9-42ee-a9bf-87d95edad83b".equals(test.getAssertionUuid().toString())) {
                // BOTH of this assertion's findings are for description
                // 3008913022 - the language refset snapshot gives it two active
                // members in refset 900000000000509007, and the assertion reports
                // one finding per member - so whitelisting that component removes
                // both and nothing is left to report.
                //
                // This expected 1 when it was written, which requires the two
                // findings to be on two DIFFERENT components. They are not on
                // this fixture: of its four duplicate (description, refset) pairs
                // only 3008913022 is in scope, and it accounts for both. Asserting
                // 1 here would be asserting a fixture nobody has.
                assertEquals(0L, test.getFailureCount().longValue());
                assertTrue(test.getFirstNInstances() == null || test.getFirstNInstances().isEmpty(),
                        "every finding was whitelisted, so there is nothing left to show");
            }
        }

        // Clear QA result
        releaseDataManager.clearQAResult(config.getExecutionId());
    }

    private List<TestRunItem> runAssertionsTest(final List<Assertion> assertions) {
        final Collection<TestRunItem> runItems = assertionExecutionService.executeAssertionsConcurrently(assertions, config);
        return new ArrayList<>(runItems);
    }

    @AfterEach
    public void tearDown() {
        rf2FilesLoaded.clear();
    }
}
