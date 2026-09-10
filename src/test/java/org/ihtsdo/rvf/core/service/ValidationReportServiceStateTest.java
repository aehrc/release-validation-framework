package org.ihtsdo.rvf.core.service;

import org.ihtsdo.otf.resourcemanager.ResourceConfiguration;
import org.ihtsdo.rvf.core.service.config.ValidationJobResourceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * When a run was submitted, and why it needs its own file.
 *
 * <p>{@code state.txt} is overwritten on every transition, so its content and
 * its timestamp both describe the LAST state change. Nothing on disk recorded
 * the moment a run was enqueued, so the console showed a run's last activity
 * where a person reads its age - "just now" for a run half an hour in, because
 * the progress file had been touched ten seconds earlier.
 *
 * <p>See the class comment on {@link ValidationRunCatalogueTest} for why the
 * store path here is relativised: {@code ResourceConfiguration.Local} strips a
 * leading separator, so an absolute path would silently become a relative one.
 */
class ValidationReportServiceStateTest {

	@TempDir
	Path store;

	private ValidationReportService service;

	@BeforeEach
	void setUp() {
		ValidationJobResourceConfig config = new ValidationJobResourceConfig();
		config.setUseCloud(false);
		config.setLocal(new ResourceConfiguration.Local(
				Path.of("").toAbsolutePath().relativize(store).toString()));
		service = new ValidationReportService();
		ReflectionTestUtils.setField(service, "jobResourceConfig", config);
		service.init();
	}

	private Path submitted(String location) {
		return store.resolve(location).resolve("rvf").resolve("submitted.txt");
	}

	private Path state(String location) {
		return store.resolve(location).resolve("rvf").resolve("state.txt");
	}

	@Test
	void queueingARunRecordsWhenItWasSubmitted() throws Exception {
		Instant before = Instant.now().minusSeconds(1);

		service.writeState(ValidationReportService.State.QUEUED, "run_a");

		assertEquals("QUEUED", Files.readString(state("run_a")).trim());
		Instant stamp = Instant.parse(Files.readString(submitted("run_a")).trim());
		assertFalse(stamp.isBefore(before), "the stamp is when this ran, not an epoch");
		assertFalse(stamp.isAfter(Instant.now().plusSeconds(1)), stamp.toString());
	}

	@Test
	void aLaterTransitionDoesNotMoveTheSubmissionTime() throws Exception {
		// The whole point: RUNNING and COMPLETE rewrite state.txt, and if they
		// rewrote this too then elapsed would reset every phase and a long run
		// would always look new.
		service.writeState(ValidationReportService.State.QUEUED, "run_b");
		String first = Files.readString(submitted("run_b"));

		service.writeState(ValidationReportService.State.RUNNING, "run_b");
		service.writeState(ValidationReportService.State.COMPLETE, "run_b");

		assertEquals("COMPLETE", Files.readString(state("run_b")).trim());
		assertEquals(first, Files.readString(submitted("run_b")),
				"submitted.txt must be written once, at QUEUED");
	}

	@Test
	void aRunThatFailsBeforeItIsQueuedHasNoSubmissionTime() throws Exception {
		// ValidationQueueManager writes FAILED for a submission it refuses
		// outright - a missing file, a blank path - and such a run never
		// entered the queue. Absent is the honest answer, and the catalogue
		// and console both handle it.
		service.writeState(ValidationReportService.State.FAILED, "run_c");

		assertEquals("FAILED", Files.readString(state("run_c")).trim());
		assertFalse(Files.exists(submitted("run_c")),
				"a run that was never queued has no submission time");
	}
}
