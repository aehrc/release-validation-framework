package org.ihtsdo.rvf.core.service;

import org.apache.commons.io.IOUtils;
import org.ihtsdo.otf.rest.exception.BusinessServiceException;
import org.ihtsdo.rvf.configuration.IntegrationTest;
import org.ihtsdo.rvf.core.service.config.MysqlExecutionConfig;
import org.ihtsdo.rvf.core.service.config.ValidationRunConfig;
import org.ihtsdo.rvf.core.service.pojo.ValidationStatusReport;
import org.ihtsdo.rvf.core.service.structure.resource.ResourceProvider;
import org.ihtsdo.rvf.core.service.structure.resource.ZipFileResourceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class ValidationVersionLoaderIntegrationTest extends IntegrationTest {
	@Autowired
	private ReleaseDataManager releaseDataManager;
	
	
	@Autowired
	private ValidationVersionLoader dataLoader;
	private String prospectiveVersion;
	private ValidationRunConfig validationConfig;
	/**
	 * The release under test.
	 *
	 * <p>setUp built this File and threw it away - `new File(...);` with no
	 * assignment - and every test then passed null where the loader wants it, so
	 * four of them died on "Prospective file can't be null" or a
	 * NullPointerException. That is what kept this class @Disabled.
	 */
	private File localProspectiveFile;
	
	@BeforeEach
	public void setUp() {
		long runId = System.currentTimeMillis();
		validationConfig = new ValidationRunConfig();
		validationConfig.setFailureExportMax(100);
		validationConfig.setRunId(runId);
		validationConfig.setGroupsList(List.of("file-centric-validation"));
		localProspectiveFile = new File(ClassLoader.getSystemResource("Daily_Export_Delta.zip").getFile());
	}
	
	@Test
	public void testConstructIntProspectiveVersionWithRF2DeltaOnly() throws Exception {
		prospectiveVersion = validationConfig.getRunId().toString();
		MysqlExecutionConfig executionConfig = new MysqlExecutionConfig(validationConfig.getRunId());
		// This calls the delta-combine step DIRECTLY rather than through
		// loadProspectiveVersion, so the prospective schema has to exist first -
		// the run id on its own is not a schema, which is what
		// "version not found in RVF database <runId>" was saying.
		executionConfig.setProspectiveVersion(releaseDataManager.createSchema("rvf_" + validationConfig.getRunId()));
		executionConfig.setRf2DeltaOnly(true);
		// A delta-only load combines the delta with the PREVIOUS snapshot, so
		// there has to be one. No previous release means the empty schema, which
		// is exactly the first-time-release case and what this used to die on:
		// "version not found in RVF database nullversion...".
		dataLoader.loadPreviousVersion(null, null, executionConfig);
		List<String> filesLoaded = dataLoader.loadProspectiveDeltaAndCombineWithPreviousSnapshotIntoDB(executionConfig, localProspectiveFile, null);
		assertEquals(1, filesLoaded.size());
		// The loader names this rvf_<from the file names>_<executionId>, so the
		// only honest source for it is the config it just wrote.
		assertTrue(releaseDataManager.isKnownRelease(executionConfig.getProspectiveVersion()),
				"the prospective schema it created should be known: " + executionConfig.getProspectiveVersion());
	}

	@Test
	public void testConstructExtensionProspectiveVersionWithRF2DeltaOnly() throws Exception {
		prospectiveVersion = validationConfig.getRunId().toString();
		validationConfig.addExtensionDependency("int_20160131");
		validationConfig.addPreviousRelease("dk_20160215");
		validationConfig.setRf2DeltaOnly(true);
		MysqlExecutionConfig executionConfig = dataLoader.createExecutionConfig(validationConfig);
		dataLoader.loadPreviousVersion(null, null, executionConfig);
		ValidationStatusReport statusReport = new ValidationStatusReport(validationConfig);
		dataLoader.loadProspectiveVersion(localProspectiveFile, statusReport, executionConfig, null);
		// The loader names this rvf_<from the file names>_<executionId>, so the
		// only honest source for it is the config it just wrote.
		assertTrue(releaseDataManager.isKnownRelease(executionConfig.getProspectiveVersion()),
				"the prospective schema it created should be known: " + executionConfig.getProspectiveVersion());
	}

	@Test
	public void testProspectiveVersion() throws Exception {
		prospectiveVersion = validationConfig.getRunId().toString();
		MysqlExecutionConfig executionConfig = dataLoader.createExecutionConfig(validationConfig);
		ValidationStatusReport statusReport = new ValidationStatusReport(validationConfig);
		dataLoader.loadProspectiveVersion(localProspectiveFile, statusReport, executionConfig, null);
		// The loader names this rvf_<from the file names>_<executionId>, so the
		// only honest source for it is the config it just wrote.
		assertTrue(releaseDataManager.isKnownRelease(executionConfig.getProspectiveVersion()),
				"the prospective schema it created should be known: " + executionConfig.getProspectiveVersion());
	}

	@Test
	public void testProspectiveVersionWithExtension() throws Exception {
		prospectiveVersion = validationConfig.getRunId().toString();
		validationConfig.addExtensionDependency("int_20160131");
		validationConfig.addPreviousRelease("dk_20160215");
		MysqlExecutionConfig executionConfig = dataLoader.createExecutionConfig(validationConfig);
		executionConfig.setRf2DeltaOnly(false);
		ValidationStatusReport statusReport = new ValidationStatusReport(validationConfig);
		dataLoader.loadProspectiveVersion(localProspectiveFile, statusReport, executionConfig, null);
		// The loader names this rvf_<from the file names>_<executionId>, so the
		// only honest source for it is the config it just wrote.
		assertTrue(releaseDataManager.isKnownRelease(executionConfig.getProspectiveVersion()),
				"the prospective schema it created should be known: " + executionConfig.getProspectiveVersion());
	} 
	
	@Test
	public void testLoadPreviousVersion() throws Exception {
		validationConfig.setPreviousRelease("SnomedCT_RF2Release_INT_20130131.zip");
		MysqlExecutionConfig executionConfig = dataLoader.createExecutionConfig(validationConfig);
		// loadPreviousVersion's observable effect is the schema it names on the
		// execution config. This asserted on a local map that nothing populates,
		// so it could only ever throw a NullPointerException.
		dataLoader.loadPreviousVersion(validationConfig.getPreviousRelease(), null, executionConfig);
		assertNotNull(executionConfig.getPreviousVersion(), "a previous version schema should be named");
		assertTrue(executionConfig.getPreviousVersion().startsWith("rvf_"),
				"the name should be an RVF schema name, not the zip: " + executionConfig.getPreviousVersion());
	}
	
	@Test
	public void testLoadPreviousIntDerivativeVersion() throws Exception {
		
		validationConfig.addExtensionDependency("int_20160131");
		validationConfig.setPreviousRelease("SnomedCT_GPFPICPC2_Production_INT_20160731.zip");
		MysqlExecutionConfig executionConfig = dataLoader.createExecutionConfig(validationConfig);
		// loadPreviousVersion's observable effect is the schema it names on the
		// execution config. This asserted on a local map that nothing populates,
		// so it could only ever throw a NullPointerException.
		dataLoader.loadPreviousVersion(validationConfig.getPreviousRelease(), null, executionConfig);
		assertNotNull(executionConfig.getPreviousVersion(), "a previous version schema should be named");
		assertTrue(executionConfig.getPreviousVersion().startsWith("rvf_"),
				"the name should be an RVF schema name, not the zip: " + executionConfig.getPreviousVersion());
	}
	
	
	@Test
	public void testLoadPreviousExtensionVersion() throws Exception {
		validationConfig.addExtensionDependency("int_20160131");
		validationConfig.setPreviousRelease("SnomedCT_RF2Release_SE1000052_20160531.zip");
		MysqlExecutionConfig executionConfig = dataLoader.createExecutionConfig(validationConfig);
		dataLoader.loadPreviousVersion(validationConfig.getPreviousRelease(), null, executionConfig);
	}
	
	@AfterEach
	public void tearDown() {
		validationConfig = null;
	}

	@Test
	public void testCopyFile() throws IOException {
		File prospectiveFile = File.createTempFile(validationConfig.getRunId() + "_" + validationConfig.getTestFileName(), ".zip");
		try (FileOutputStream out = new FileOutputStream(prospectiveFile); InputStream input = new FileInputStream(ClassLoader.getSystemResource("Daily_Export_Delta.zip").getFile())) {
			IOUtils.copy(input, out);
		}
		assertTrue(prospectiveFile.isFile());
		ResourceProvider resourceManager = new ZipFileResourceProvider(prospectiveFile);
		assertFalse(resourceManager.getFileNames().isEmpty());
	}
}
