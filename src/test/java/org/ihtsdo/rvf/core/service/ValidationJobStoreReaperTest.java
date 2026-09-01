package org.ihtsdo.rvf.core.service;

import org.ihtsdo.otf.resourcemanager.ResourceConfiguration;
import org.ihtsdo.rvf.core.service.config.ValidationJobResourceConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reaper must reclaim uploaded releases and never a report.
 *
 * <p>The asymmetry is the whole point and is easy to get backwards: the release
 * package is the biggest artefact (853MB for the AU edition) and the most
 * replaceable, while the report is 37KB and irreplaceable - it is what the
 * dashboard and CI links resolve through. A reaper that deleted a report to save
 * space would be destroying the only copy of the answer.
 */
class ValidationJobStoreReaperTest {

	private ValidationJobStoreReaper reaper(Path root, int days) {
		ValidationJobResourceConfig config = new ValidationJobResourceConfig();
		config.setUseCloud(false);
		config.setLocal(new ResourceConfiguration.Local(root.toString()));
		return new ValidationJobStoreReaper(config, days);
	}

	/** A finished run: an uploaded release plus the four report files RVF writes. */
	private Path run(Path root, String storageLocation, Instant age) throws IOException {
		Path job = root.resolve(storageLocation);
		Path uploads = job.resolve(ValidationJobStoreReaper.FILES_TO_VALIDATE);
		Path rvf = job.resolve("rvf");
		Files.createDirectories(uploads);
		Files.createDirectories(rvf);
		write(uploads.resolve("SnomedCT_Release.zip"), "a release package", age);
		write(uploads.resolve("manifest.xml"), "<manifest/>", age);
		for (String report : new String[]{"results.json", "state.txt", "progress.txt", "structure_validation.txt"}) {
			write(rvf.resolve(report), "report content", age);
		}
		write(rvf.resolve("failures.parquet"), "parquet bytes", age);
		return job;
	}

	private void write(Path file, String content, Instant modified) throws IOException {
		Files.writeString(file, content);
		Files.setLastModifiedTime(file, FileTime.from(modified));
	}

	@Test
	void anAgedRunLosesItsUploadAndKeepsEveryReport(@TempDir Path root) throws IOException {
		Path job = run(root, "int/2026-01-01/111", Instant.now().minus(Duration.ofDays(30)));

		ValidationJobStoreReaper.Result result = reaper(root, 7)
				.reap(root, Instant.now().minus(Duration.ofDays(7)));

		assertEquals(2, result.files(), "the release package and its manifest");
		assertFalse(Files.exists(job.resolve("files_to_validate/SnomedCT_Release.zip")));
		assertFalse(Files.exists(job.resolve("files_to_validate/manifest.xml")));

		for (String kept : new String[]{"results.json", "state.txt", "progress.txt",
				"structure_validation.txt", "failures.parquet"}) {
			assertTrue(Files.exists(job.resolve("rvf").resolve(kept)),
					kept + " is irreplaceable and must survive reaping");
		}
	}

	@Test
	void aRecentRunIsLeftEntirelyAlone(@TempDir Path root) throws IOException {
		Path job = run(root, "int/2026-01-01/222", Instant.now().minus(Duration.ofDays(2)));

		ValidationJobStoreReaper.Result result = reaper(root, 7)
				.reap(root, Instant.now().minus(Duration.ofDays(7)));

		assertEquals(0, result.files());
		assertTrue(Files.exists(job.resolve("files_to_validate/SnomedCT_Release.zip")),
				"a run inside the window may still be re-run and needs its input");
	}

	@Test
	void theDirectoryItselfSurvivesSoAReRunCanRestageIntoIt(@TempDir Path root) throws IOException {
		Path job = run(root, "int/2026-01-01/333", Instant.now().minus(Duration.ofDays(30)));

		reaper(root, 7).reap(root, Instant.now().minus(Duration.ofDays(7)));

		assertTrue(Files.isDirectory(job.resolve("files_to_validate")),
				"removing the directory would make a restage create it again for no gain");
	}

	@Test
	void onlyFilesToValidateIsEligibleEvenWhenEverythingIsOld(@TempDir Path root) throws IOException {
		// A stray old file directly under the job directory, outside both known
		// subdirectories, must not be swept up by a broadening of the walk.
		Path job = run(root, "int/2026-01-01/444", Instant.now().minus(Duration.ofDays(90)));
		write(job.resolve("something-else.txt"), "not ours to delete",
				Instant.now().minus(Duration.ofDays(90)));

		ValidationJobStoreReaper.Result result = reaper(root, 7)
				.reap(root, Instant.now().minus(Duration.ofDays(7)));

		assertEquals(2, result.files(), "still only the two upload files");
		assertTrue(Files.exists(job.resolve("something-else.txt")));
	}

	@Test
	void manyRunsAreReapedIndependentlyByAge(@TempDir Path root) throws IOException {
		Path old1 = run(root, "int/a/1", Instant.now().minus(Duration.ofDays(20)));
		Path old2 = run(root, "int/b/2", Instant.now().minus(Duration.ofDays(8)));
		Path fresh = run(root, "int/c/3", Instant.now().minus(Duration.ofHours(6)));

		ValidationJobStoreReaper.Result result = reaper(root, 7)
				.reap(root, Instant.now().minus(Duration.ofDays(7)));

		assertEquals(4, result.files(), "two files from each of the two aged runs");
		assertFalse(Files.exists(old1.resolve("files_to_validate/SnomedCT_Release.zip")));
		assertFalse(Files.exists(old2.resolve("files_to_validate/SnomedCT_Release.zip")));
		assertTrue(Files.exists(fresh.resolve("files_to_validate/SnomedCT_Release.zip")));
	}

	@Test
	void bytesReclaimedAreReportedSoTheLogIsWorthReading(@TempDir Path root) throws IOException {
		Path job = run(root, "int/2026-01-01/555", Instant.now().minus(Duration.ofDays(30)));
		long expected = Files.size(job.resolve("files_to_validate/SnomedCT_Release.zip"))
				+ Files.size(job.resolve("files_to_validate/manifest.xml"));

		ValidationJobStoreReaper.Result result = reaper(root, 7)
				.reap(root, Instant.now().minus(Duration.ofDays(7)));

		assertEquals(expected, result.bytes());
	}

	@Test
	void zeroDaysDisablesReapingEntirely(@TempDir Path root) throws IOException {
		Path job = run(root, "int/2026-01-01/666", Instant.now().minus(Duration.ofDays(365)));

		reaper(root, 0).reap();

		assertTrue(Files.exists(job.resolve("files_to_validate/SnomedCT_Release.zip")),
				"retention 0 must preserve today's behaviour of keeping everything");
	}

	@Test
	void aCloudBackedJobStoreIsLeftToItsLifecycleRules(@TempDir Path root) throws IOException {
		Path job = run(root, "int/2026-01-01/777", Instant.now().minus(Duration.ofDays(365)));
		ValidationJobResourceConfig config = new ValidationJobResourceConfig();
		config.setUseCloud(true);
		config.setCloud(new ResourceConfiguration.Cloud("a-bucket", ""));
		config.setLocal(new ResourceConfiguration.Local(root.toString()));

		new ValidationJobStoreReaper(config, 7).reap();

		assertTrue(Files.exists(job.resolve("files_to_validate/SnomedCT_Release.zip")),
				"object stores express this server-side; a second implementation here would be worse");
	}

	@Test
	void anAbsentJobStoreRootIsNotAnError(@TempDir Path root) {
		reaper(root.resolve("never-created"), 7).reap();
	}
}
