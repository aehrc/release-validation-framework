package org.ihtsdo.rvf.core.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The contract this has to keep is <em>what snomedboot would have produced</em>:
 * one flat directory holding exactly the files of the requested release type,
 * byte for byte. Everything else about it - the thread pool, the ordering - is
 * an implementation detail that must not change the loader's input.
 */
class RF2ReleaseTypeUnpackerTest {

	@TempDir
	Path work;

	private File archive(String name, String... entries) throws IOException {
		Path zip = work.resolve(name);
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			for (String entry : entries) {
				out.putNextEntry(new ZipEntry(entry));
				out.write(("content of " + entry).getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			}
		}
		return zip.toFile();
	}

	private Set<String> namesIn(Path dir) throws IOException {
		try (var files = Files.list(dir)) {
			return files.map(p -> p.getFileName().toString()).collect(Collectors.toSet());
		}
	}

	@Test
	void onlyTheRequestedReleaseTypeIsExtracted() throws IOException {
		File zip = archive("release.zip",
				"SnomedCT/Snapshot/Terminology/sct2_Concept_Snapshot_AU1000036_20260831.txt",
				"SnomedCT/Delta/Terminology/sct2_Concept_Delta_AU1000036_20260831.txt",
				"SnomedCT/Full/Terminology/sct2_Concept_Full_AU1000036_20260831.txt");

		Path out = RF2ReleaseTypeUnpacker.unpack(zip, work, "Snapshot");

		assertEquals(Set.of("sct2_Concept_Snapshot_AU1000036_20260831.txt"), namesIn(out),
				"extracting Full and Delta as well would treble what Drools writes to disk");
	}

	/**
	 * The directory must be flat. snomedboot picks files out of it by filename,
	 * and a preserved {@code Snapshot/Terminology/} tree would leave the loader
	 * finding nothing - which reads as an empty release, not an error.
	 */
	@Test
	void theDirectoryIsFlat() throws IOException {
		File zip = archive("release.zip",
				"SnomedCT/Snapshot/Terminology/sct2_Concept_Snapshot_INT_20260801.txt",
				"SnomedCT/Snapshot/Refset/Language/der2_cRefset_LanguageSnapshot-en_INT_20260801.txt");

		Path out = RF2ReleaseTypeUnpacker.unpack(zip, work, "Snapshot");

		assertEquals(Set.of("sct2_Concept_Snapshot_INT_20260801.txt",
						"der2_cRefset_LanguageSnapshot-en_INT_20260801.txt"),
				namesIn(out));
		try (var files = Files.list(out)) {
			assertTrue(files.allMatch(Files::isRegularFile), "no subdirectories");
		}
	}

	@Test
	void contentSurvivesExtractionIntact() throws IOException {
		File zip = archive("release.zip",
				"Snapshot/sct2_Concept_Snapshot_INT.txt",
				"Snapshot/sct2_Description_Snapshot-en_INT.txt");

		Path out = RF2ReleaseTypeUnpacker.unpack(zip, work, "Snapshot");

		assertEquals("content of Snapshot/sct2_Concept_Snapshot_INT.txt",
				Files.readString(out.resolve("sct2_Concept_Snapshot_INT.txt")));
		assertEquals("content of Snapshot/sct2_Description_Snapshot-en_INT.txt",
				Files.readString(out.resolve("sct2_Description_Snapshot-en_INT.txt")));
	}

	/**
	 * Language-suffixed names are the reason this is a substring test:
	 * {@code der2_cRefset_LanguageSnapshot-en_...} has no {@code _Snapshot_}.
	 */
	@Test
	void languageSuffixedFilesAreNotMissed() {
		assertTrue(RF2ReleaseTypeUnpacker.keep(
				"x/der2_cRefset_LanguageSnapshot-en_AU1000036_20260831.txt", "Snapshot"));
		assertTrue(RF2ReleaseTypeUnpacker.keep(
				"x/sct2_Description_Snapshot-en_AU1000036_20260831.txt", "Snapshot"));
	}

	@Test
	void nonRf2NoiseIsSkipped() {
		assertFalse(RF2ReleaseTypeUnpacker.keep("__MACOSX/._sct2_Concept_Snapshot_INT.txt", "Snapshot"));
		assertFalse(RF2ReleaseTypeUnpacker.keep("x/.DS_Store", "Snapshot"));
		assertFalse(RF2ReleaseTypeUnpacker.keep("x/README_Snapshot.pdf", "Snapshot"),
				"only .txt files are RF2");
		assertFalse(RF2ReleaseTypeUnpacker.keep("x/sct2_Concept_Delta_INT.txt", "Snapshot"));
	}

	@Test
	void deltaAndFullAreSelectableToo() throws IOException {
		File zip = archive("release.zip",
				"sct2_Concept_Snapshot_INT.txt",
				"sct2_Concept_Delta_INT.txt",
				"sct2_Concept_Full_INT.txt");

		assertEquals(Set.of("sct2_Concept_Delta_INT.txt"),
				namesIn(RF2ReleaseTypeUnpacker.unpack(zip, work, "Delta")));
		assertEquals(Set.of("sct2_Concept_Full_INT.txt"),
				namesIn(RF2ReleaseTypeUnpacker.unpack(zip, work, "Full")));
	}

	/**
	 * Every call gets its own directory. Two editions are unpacked in the same
	 * run and their filenames are identical, so a shared directory would have the
	 * previous release silently overwriting the prospective one.
	 */
	@Test
	void twoEditionsDoNotCollide() throws IOException {
		File first = archive("prospective.zip", "sct2_Concept_Snapshot_AU.txt");
		File second = archive("previous.zip", "sct2_Concept_Snapshot_AU.txt");

		Path a = RF2ReleaseTypeUnpacker.unpack(first, work, "Snapshot");
		Path b = RF2ReleaseTypeUnpacker.unpack(second, work, "Snapshot");

		assertFalse(a.equals(b), "each unpack needs its own directory");
		assertEquals("content of sct2_Concept_Snapshot_AU.txt",
				Files.readString(a.resolve("sct2_Concept_Snapshot_AU.txt")));
		assertEquals("content of sct2_Concept_Snapshot_AU.txt",
				Files.readString(b.resolve("sct2_Concept_Snapshot_AU.txt")));
	}

	/**
	 * A truncated directory is worse than a failed run: a missing file reads as
	 * an empty table, so the validation would pass having checked less.
	 */
	@Test
	void anUnreadableArchiveFailsRatherThanYieldingAPartialDirectory() throws IOException {
		Path notAZip = work.resolve("broken.zip");
		Files.writeString(notAZip, "this is not a zip archive");

		assertTrue(List.of(IOException.class, java.util.zip.ZipException.class).stream()
						.anyMatch(t -> {
							try {
								RF2ReleaseTypeUnpacker.unpack(notAZip.toFile(), work, "Snapshot");
								return false;
							} catch (Exception e) {
								return t.isInstance(e);
							}
						}),
				"a corrupt archive must raise, not return an empty directory");
	}
}
