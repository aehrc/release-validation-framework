package org.ihtsdo.rvf.core.service;

import org.ihtsdo.otf.resourcemanager.ManualResourceConfiguration;
import org.ihtsdo.otf.resourcemanager.ResourceConfiguration;
import org.ihtsdo.otf.resourcemanager.ResourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeping a release, and reporting it under the name a validation can ask for.
 *
 * <p>The name is the contract, and it is the easy thing to get wrong.
 * {@code ReleaseAcquisitionService} resolves {@code previousRelease} by reading
 * that exact string as a filename from release storage, so a catalogue that
 * reported anything else - a version, a schema-style name, a path - would list
 * releases that cannot then be requested. Every test here is about that
 * round trip.
 */
class StoredReleaseCatalogueTest {

	/*
	 * A RELATIVE directory, and not @TempDir, for a reason worth stating.
	 * ResourceConfiguration.normalisePath() strips a leading '/', so an absolute
	 * store path silently becomes relative to the process working directory: an
	 * earlier version of this test passed an absolute @TempDir and its files
	 * landed under ./data/work/tmp/junit-*, inside the repository. Production has
	 * the same semantics, so the test uses them too.
	 */
	private final Path root = Path.of("target", "release-store-test-" + System.nanoTime());

	@AfterEach
	void cleanUp() throws IOException {
		if (Files.isDirectory(root)) {
			try (var paths = Files.walk(root)) {
				paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
					try {
						Files.delete(p);
					} catch (IOException ignored) {
						// best effort under target/
					}
				});
			}
		}
	}

	private StoredReleaseCatalogue catalogue() {
		ResourceConfiguration config = new ManualResourceConfiguration(false, false,
				new ResourceConfiguration.Local(root.toString() + "/"), null);
		return new StoredReleaseCatalogue(new ResourceManager(config, new DefaultResourceLoader()));
	}

	private MockMultipartFile release(String filename) {
		return new MockMultipartFile("file", filename, "application/zip", "PK\u0003\u0004 rf2".getBytes());
	}

	@Test
	void aKeptReleaseIsReportedUnderTheNameAValidationWillAskFor() throws Exception {
		StoredReleaseCatalogue catalogue = catalogue();

		String name = catalogue.store(release("SnomedCT_AU_20260731.zip"), "au", "20260731");

		assertEquals("SnomedCT_AU_20260731.zip", name,
				"the returned name is what the caller passes back as previousRelease");
		assertTrue(catalogue.names().contains(name));
		assertTrue(catalogue.contains(name));
		assertTrue(Files.exists(root.resolve("SnomedCT_AU_20260731.zip")));
	}

	@Test
	void productAndVersionDoNotRenameTheStoredPackage() throws Exception {
		// They are MySQL's schema-naming convention. Honouring them here would
		// produce a name release storage cannot resolve.
		String name = catalogue().store(release("SnomedCT_AU_20260731.zip"), "au", "20260731");

		assertEquals("SnomedCT_AU_20260731.zip", name);
		assertFalse(name.startsWith("rvf_"), "rvf_{product}_{version} is a schema name, not a filename");
	}

	@Test
	void severalKeptReleasesAreListedSortedAndChronological() throws Exception {
		StoredReleaseCatalogue catalogue = catalogue();
		catalogue.store(release("SnomedCT_AU_20260731.zip"), "au", "20260731");
		catalogue.store(release("SnomedCT_AU_20260630.zip"), "au", "20260630");
		catalogue.store(release("SnomedCT_INT_20260801.zip"), "int", "20260801");

		assertEquals(3, catalogue.names().size());
		assertEquals("SnomedCT_AU_20260630.zip", catalogue.names().iterator().next(),
				"sorted, and release names sort chronologically within a product by construction");
	}

	@Test
	void onlyZipPackagesAreOffered() throws Exception {
		StoredReleaseCatalogue catalogue = catalogue();
		catalogue.store(release("SnomedCT_AU_20260731.zip"), "au", "20260731");
		Files.writeString(root.resolve("notes.txt"), "left here by a human");
		Files.writeString(root.resolve("manifest.xml"), "<manifest/>");

		assertEquals(Set.of("SnomedCT_AU_20260731.zip"), catalogue.names(),
				"a release is an RF2 package; anything else in the directory is not one");
	}

	@Test
	void anUnknownNameIsAbsentRatherThanAnError() {
		StoredReleaseCatalogue catalogue = catalogue();

		assertFalse(catalogue.contains("SnomedCT_AU_19990101.zip"));
		assertFalse(catalogue.contains(null), "a null previousRelease is simply not available");
		assertFalse(catalogue.contains("  "));
	}

	@Test
	void anEmptyStoreIsNoReleasesNotAFailure() {
		assertEquals(Set.of(), catalogue().names(),
				"a fresh deployment keeps nothing yet and should say so plainly");
	}

	@Test
	void theStoreDirectoryIsRelativeToTheWorkingDirectory() throws Exception {
		// Pins the normalisePath() behaviour that made an absolute path silently
		// wrong. If this ever starts resolving absolutely, deployments that mount
		// a share and configure it with a leading slash change meaning.
		catalogue().store(release("SnomedCT_AU_20260731.zip"), "au", "20260731");

		assertTrue(Files.exists(Path.of("").toAbsolutePath().resolve(root)
						.resolve("SnomedCT_AU_20260731.zip")),
				"the configured path resolves against the process working directory");
	}

	@Test
	void aPackageWithNoFilenameIsRefusedWithTheReason() {
		MockMultipartFile nameless = new MockMultipartFile("file", "", "application/zip", "PK".getBytes());

		IOException e = assertThrows(IOException.class,
				() -> catalogue().store(nameless, "au", "20260731"));
		assertTrue(e.getMessage().contains("filename"),
				"the filename IS the name a validation asks for, so its absence is the whole problem");
	}
}
