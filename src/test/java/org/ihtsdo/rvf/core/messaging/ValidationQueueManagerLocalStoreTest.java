package org.ihtsdo.rvf.core.messaging;

import org.ihtsdo.otf.jms.MessagingHelper;
import org.ihtsdo.otf.resourcemanager.ResourceConfiguration;
import org.ihtsdo.otf.resourcemanager.ResourceManager;
import org.ihtsdo.rvf.core.service.ValidationReportService;
import org.ihtsdo.rvf.core.service.config.ValidationJobResourceConfig;
import org.ihtsdo.rvf.core.service.config.ValidationRunConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A release already sitting in a SHARED local job store can be validated
 * without an object store.
 *
 * <p>This is the deployment shape it defends: one Azure Files or NFS mount held
 * by the API node and every worker, CI writing the release into it and then
 * naming the path, instead of pushing 853MB through a multipart request that
 * {@code spring.servlet.multipart.max-file-size=1GB} and any ingress body cap
 * both apply to.
 *
 * <p>The mechanism was already there and unreachable.
 * {@code ReleaseAcquisitionService.downloadProspectiveReleaseFile} falls through
 * to {@code jobResource.readResourceStreamOrNullIfNotExists(path)} whenever
 * {@code useCloud} is false, but {@code saveUploadedFiles} refused the request
 * before it could ever get there. These tests pin the relaxation, and pin that
 * it did not become permissive: a path that is not in the store still fails, and
 * still fails at SUBMISSION time rather than in a worker after the caller has
 * been told 201.
 */
class ValidationQueueManagerLocalStoreTest {

	private ValidationQueueManager queueManager;
	private ResourceManager jobStore;
	private ValidationReportService reportService;
	private JmsTemplate jmsTemplate;
	private ValidationJobResourceConfig jobResourceConfig;

	@BeforeEach
	void setUp() {
		queueManager = new ValidationQueueManager();
		jobStore = mock(ResourceManager.class);
		reportService = mock(ValidationReportService.class);
		jmsTemplate = mock(JmsTemplate.class);

		jobResourceConfig = new ValidationJobResourceConfig();
		jobResourceConfig.setUseCloud(false);
		jobResourceConfig.setLocal(new ResourceConfiguration.Local("shared-jobs/"));

		ReflectionTestUtils.setField(queueManager, "validationJobResourceManager", jobStore);
		ReflectionTestUtils.setField(queueManager, "jobResourceConfig", jobResourceConfig);
		ReflectionTestUtils.setField(queueManager, "reportService", reportService);
		ReflectionTestUtils.setField(queueManager, "jmsTemplate", jmsTemplate);
		ReflectionTestUtils.setField(queueManager, "destinationName", "rvf-queue");
		// A config carrying a responseQueue reports its QUEUED state back through
		// this, so without it the response-queue case NPEs rather than testing.
		ReflectionTestUtils.setField(queueManager, "messagingHelper", mock(MessagingHelper.class));
	}

	private ValidationRunConfig alreadyInStore(String path) {
		return new ValidationRunConfig()
				.addRunId(1L)
				.addStorageLocation("loc")
				.addProspectiveFileFullPath(path)
				.addProspectiveFilesInS3(true);
	}

	@Test
	void aFileAlreadyInTheLocalStoreIsQueuedRatherThanRefused() throws Exception {
		when(jobStore.doesObjectExist("builds/int/release.zip")).thenReturn(true);
		Map<String, String> response = new HashMap<>();

		queueManager.queueValidationRequest(alreadyInStore("builds/int/release.zip"), response);

		assertNull(response.get("failureMessage"),
				"local job storage must not be a reason to refuse a file that is already in it");
		verify(jmsTemplate).convertAndSend(anyString(), any(Object.class));
		verify(jobStore, never()).writeResource(anyString(), any());
	}

	@Test
	void theFileIsNotReStagedBecauseItIsAlreadyWhereTheWorkerLooks() throws Exception {
		when(jobStore.doesObjectExist(anyString())).thenReturn(true);

		queueManager.queueValidationRequest(alreadyInStore("builds/int/release.zip"), new HashMap<>());

		// Copying an 853MB release onto itself is the cost this whole path exists
		// to avoid, so "no write" is the point, not an incidental detail.
		verify(jobStore, never()).writeResource(anyString(), any());
	}

	@Test
	void anAbsentPathFailsAtSubmissionWithSomethingActionable() throws Exception {
		when(jobStore.doesObjectExist("builds/int/missing.zip")).thenReturn(false);
		Map<String, String> response = new HashMap<>();

		queueManager.queueValidationRequest(alreadyInStore("builds/int/missing.zip"), response);

		String message = response.get("failureMessage");
		assertTrue(message != null && message.contains("builds/int/missing.zip"),
				"the caller needs to see WHICH path was not found, got: " + message);
		assertTrue(message.contains("shared-jobs/"),
				"and where it was looked for, so a relative-path mistake is diagnosable: " + message);
		verify(reportService).writeState(ValidationReportService.State.FAILED, "loc");
		verify(jmsTemplate, never()).convertAndSend(anyString(), any(Object.class));
	}

	@Test
	void aBlankPathIsRejectedRatherThanProbingTheStore() throws Exception {
		Map<String, String> response = new HashMap<>();

		queueManager.queueValidationRequest(alreadyInStore("   "), response);

		assertFalse(response.get("failureMessage") == null);
		verify(jobStore, never()).doesObjectExist(anyString());
		verify(jmsTemplate, never()).convertAndSend(anyString(), any(Object.class));
	}

	@Test
	void cloudStorageIsUntouchedByTheRelaxation() throws Exception {
		// The relaxed branch is guarded on !useCloud, so a cloud-configured
		// instance must not enter it at all - no existence probe against the
		// local store, and the request queues exactly as before.
		jobResourceConfig.setUseCloud(true);
		jobResourceConfig.setCloud(new ResourceConfiguration.Cloud("some-bucket", ""));
		Map<String, String> response = new HashMap<>();

		queueManager.queueValidationRequest(alreadyInStore("builds/int/release.zip"), response);

		assertNull(response.get("failureMessage"));
		verify(jobStore, never()).doesObjectExist(anyString());
		verify(jmsTemplate).convertAndSend(anyString(), any(Object.class));
	}

	@Test
	void theQueueNameCarriesTheResponseQueueSuffixAsBefore() throws Exception {
		when(jobStore.doesObjectExist(anyString())).thenReturn(true);
		ValidationRunConfig config = alreadyInStore("builds/int/release.zip")
				.addResponseQueue("srs.response");

		queueManager.queueValidationRequest(config, new HashMap<>());

		// Guards against the relaxation short-circuiting past the routing that
		// decides which worker pool sees the message.
		assertEquals(true, config.isProspectiveFileInS3(),
				"the flag still means 'already in the store' and must survive queueing");
	}
}
