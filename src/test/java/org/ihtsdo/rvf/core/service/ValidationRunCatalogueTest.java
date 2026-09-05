package org.ihtsdo.rvf.core.service;

import org.ihtsdo.otf.resourcemanager.ResourceConfiguration;
import org.ihtsdo.rvf.core.service.config.ValidationJobResourceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The catalogue reads reports by walking the JSON token stream and skipping the
 * assertion arrays, so these fixtures carry those arrays. A parser that read
 * them would still pass a test whose fixtures omitted them.
 *
 * <p><b>The store path has to be relative.</b>
 * {@code ResourceConfiguration.Local} strips a leading separator, so setting an
 * absolute path silently yields a relative one - {@code /tmp/x} becomes
 * {@code tmp/x} - which then resolves against the working directory and finds
 * nothing. That is why the deployed configuration says {@code jobs/} rather
 * than {@code /app/jobs}, with the PVC mounted at {@code /app/jobs} and the
 * container's working directory {@code /app}. These tests therefore relativise
 * the temporary directory against the working directory, which is what
 * production does.
 */
class ValidationRunCatalogueTest {

	@TempDir
	Path store;

	private ValidationRunCatalogue catalogue() {
		ValidationJobResourceConfig config = new ValidationJobResourceConfig();
		config.setUseCloud(false);
		config.setLocal(new ResourceConfiguration.Local(relativeStore(store)));
		ValidationRunCatalogue catalogue = new ValidationRunCatalogue();
		ReflectionTestUtils.setField(catalogue, "jobResourceConfig", config);
		return catalogue;
	}


	/** See the class comment: an absolute path here would be silently mangled. */
	private static String relativeStore(Path dir) {
		return Path.of("").toAbsolutePath().relativize(dir).toString();
	}

	private void writeRun(String location, String state, String results) throws IOException {
		Path rvf = Files.createDirectories(store.resolve(location).resolve("rvf"));
		Files.writeString(rvf.resolve("state.txt"), state);
		if (results != null) {
			Files.writeString(rvf.resolve("results.json"), results);
		}
	}

	/** The shape ValidationReportService writes, including the arrays to skip. */
	private static String report(long runId, String file, int run, int failures) {
		return """
				{
				  "status": "COMPLETE",
				  "rvfValidationResult": {
				    "validationConfig": {
				      "testFileName": "%s",
				      "runId": %d,
				      "groupsList": ["file-centric-validation", "amtv4"],
				      "storageLocation": "ignored"
				    },
				    "reportSummary": { "SQL": "Validations executed. Failures count: %d" },
				    "failureMessages": [],
				    "TestResult": {
				      "executionId": %d,
				      "totalTestsRun": %d,
				      "totalFailures": %d,
				      "totalWarnings": 1,
				      "assertionsFailed": [
				        { "assertionText": "a", "firstNInstances": [ { "id": "1" }, { "id": "2" } ] }
				      ],
				      "assertionsPassed": [ { "assertionText": "b" }, { "assertionText": "c" } ],
				      "assertionsSkipped": [],
				      "assertionsWarning": [ { "assertionText": "d" } ]
				    },
				    "startTime": "Aug 30, 2026, 8:28:18 PM",
				    "endTime": "Aug 30, 2026, 9:28:05 PM",
				    "totalRF2FilesLoaded": 66,
				    "rf2Files": ["a.txt", "b.txt"]
				  }
				}
				""".formatted(file, runId, failures, runId, run, failures);
	}

	@Test
	void readsTheHeadlineFieldsWithoutTheAssertionArrays() throws IOException {
		writeRun("run_a", "COMPLETE", report(1788610878L, "SnomedCT_test1.zip", 62, 1));

		List<ValidationRunCatalogue.RunSummary> runs = catalogue().list(50);

		assertEquals(1, runs.size());
		ValidationRunCatalogue.RunSummary r = runs.get(0);
		assertEquals("run_a", r.storageLocation());
		assertEquals(1788610878L, r.runId());
		assertEquals("COMPLETE", r.state());
		assertEquals("SnomedCT_test1.zip", r.testFileName());
		assertEquals("file-centric-validation, amtv4", r.groups());
		assertEquals(62, r.totalTestsRun());
		assertEquals(1, r.totalFailures());
		assertEquals(1, r.totalWarnings());
		assertEquals("Aug 30, 2026, 8:28:18 PM", r.startTime());
	}

	@Test
	void listsARunThatHasNoReportYet() throws IOException {
		// State is written when the run is queued, long before any report exists.
		// Such a run still has to appear, or the console cannot show that
		// anything is happening.
		writeRun("run_queued", "QUEUED", null);

		List<ValidationRunCatalogue.RunSummary> runs = catalogue().list(50);

		assertEquals(1, runs.size());
		assertEquals("QUEUED", runs.get(0).state());
		assertNull(runs.get(0).runId());
		assertNull(runs.get(0).totalTestsRun());
	}

	@Test
	void ignoresDirectoriesThatAreNotRuns() throws IOException {
		writeRun("real_run", "COMPLETE", report(1L, "x.zip", 1, 0));
		// This is what an uploaded release looks like in the same store.
		Files.createDirectories(store.resolve("files_to_validate"));
		Files.writeString(store.resolve("files_to_validate").resolve("release.zip"), "not json");

		List<ValidationRunCatalogue.RunSummary> runs = catalogue().list(50);

		assertEquals(1, runs.size());
		assertEquals("real_run", runs.get(0).storageLocation());
	}

	@Test
	void newestFirstByStateFileNotByDirectory() throws IOException {
		writeRun("older", "COMPLETE", report(1L, "a.zip", 1, 0));
		writeRun("newer", "COMPLETE", report(2L, "b.zip", 2, 0));
		// A run whose directory is old but which has just finished must sort
		// first, which is why the state file is what gets compared.
		Files.setLastModifiedTime(store.resolve("older/rvf/state.txt"),
				java.nio.file.attribute.FileTime.fromMillis(1_000L));
		Files.setLastModifiedTime(store.resolve("newer/rvf/state.txt"),
				java.nio.file.attribute.FileTime.fromMillis(9_000_000_000L));

		List<ValidationRunCatalogue.RunSummary> runs = catalogue().list(50);

		assertEquals(List.of("newer", "older"), runs.stream().map(ValidationRunCatalogue.RunSummary::storageLocation).toList());
	}

	@Test
	void honoursTheLimit() throws IOException {
		for (int i = 0; i < 5; i++) {
			writeRun("run_" + i, "COMPLETE", report(i, "f.zip", 1, 0));
		}
		assertEquals(2, catalogue().list(2).size());
	}

	@Test
	void survivesAReportThatIsNotValidJson() throws IOException {
		writeRun("broken", "COMPLETE", "{ this is not json");

		List<ValidationRunCatalogue.RunSummary> runs = catalogue().list(50);

		// The run still appears with its state; one unreadable report must not
		// remove every other run from the list.
		assertEquals(1, runs.size());
		assertEquals("broken", runs.get(0).storageLocation());
		assertEquals("COMPLETE", runs.get(0).state());
		assertNull(runs.get(0).runId());
	}

	@Test
	void emptyWhenTheStoreDoesNotExistYet() {
		ValidationJobResourceConfig config = new ValidationJobResourceConfig();
		config.setUseCloud(false);
		config.setLocal(new ResourceConfiguration.Local(relativeStore(store.resolve("nothing-here"))));
		ValidationRunCatalogue catalogue = new ValidationRunCatalogue();
		ReflectionTestUtils.setField(catalogue, "jobResourceConfig", config);

		assertTrue(catalogue.list(50).isEmpty());
	}
}
