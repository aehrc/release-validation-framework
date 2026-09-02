package org.ihtsdo.rvf.core.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Extracts one release type out of an RF2 archive, across all cores.
 *
 * <p>snomedboot's {@code ReleaseImporter.unzipRelease} does the same job with a
 * single thread. On the AU edition that is <b>82 seconds</b> for the two
 * archives a nightly needs - measured as the gap between the Drools phase
 * starting and its own timer beginning - against a whole-archive extraction at
 * 1,223 MB/s, 4.5 s, when the work is spread over eight cores. Nothing about
 * unzipping is inherently serial: entries are independent and
 * {@link ZipFile#getInputStream} is safe to call from several threads.
 *
 * <p>Produces exactly what snomedboot produces: <b>one flat directory</b> of the
 * matching {@code .txt} files. The loader then picks files out of it by
 * filename, which is why a flat directory is enough and why extracting only one
 * release type keeps the disk footprint to what it was. That matters - a
 * two-edition run already died once with "No space left on device", and
 * extracting Full and Delta as well would treble what Drools writes.
 *
 * <p>Largest-first, so the long pole starts immediately rather than being picked
 * up last by a thread that then runs alone.
 */
public final class RF2ReleaseTypeUnpacker {

	private static final Logger LOGGER = LoggerFactory.getLogger(RF2ReleaseTypeUnpacker.class);

	private RF2ReleaseTypeUnpacker() {
	}

	/**
	 * @param releaseType the RF2 release type token to keep - {@code Snapshot},
	 *                    {@code Delta} or {@code Full}. Matched against the entry
	 *                    name the same way the loader matches it afterwards.
	 * @return the directory holding the extracted files
	 */
	public static Path unpack(File zip, Path parent, String releaseType) throws IOException {
		long start = System.currentTimeMillis();
		Path target = Files.createTempDirectory(parent, "rf2-" + releaseType.toLowerCase() + "-");

		try (ZipFile archive = new ZipFile(zip)) {
			List<ZipEntry> wanted = new ArrayList<>();
			archive.stream()
					.filter(entry -> !entry.isDirectory())
					.filter(entry -> keep(entry.getName(), releaseType))
					.forEach(wanted::add);
			// Largest first: a 6-million-row language refset finishing last would
			// leave every other thread idle waiting for it.
			wanted.sort(Comparator.comparingLong(ZipEntry::getSize).reversed());

			int threads = Math.min(wanted.size(), Runtime.getRuntime().availableProcessors());
			if (threads > 1) {
				extractConcurrently(archive, wanted, target, threads);
			} else {
				for (ZipEntry entry : wanted) {
					extract(archive, entry, target);
				}
			}

			LOGGER.info("unpacked {} {} file(s) from {} into {} in {} ms",
					wanted.size(), releaseType, zip.getName(), target,
					System.currentTimeMillis() - start);
		}
		return target;
	}

	private static void extractConcurrently(ZipFile archive, List<ZipEntry> wanted, Path target, int threads)
			throws IOException {
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
			for (ZipEntry entry : wanted) {
				futures.add(pool.submit(() -> {
					try {
						extract(archive, entry, target);
					} catch (IOException e) {
						throw new UncheckedIOException(e);
					}
				}));
			}
			for (java.util.concurrent.Future<?> future : futures) {
				try {
					future.get();
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while unpacking " + archive.getName(), e);
				} catch (java.util.concurrent.ExecutionException e) {
					// A half-written directory would be loaded as though it were
					// complete, and a missing file reads as an empty table rather
					// than an error, so this must abort the run.
					throw new IOException("Failed to unpack " + archive.getName(), e.getCause());
				}
			}
		}
	}

	private static void extract(ZipFile archive, ZipEntry entry, Path target) throws IOException {
		Path out = target.resolve(new File(entry.getName()).getName());
		try (InputStream in = archive.getInputStream(entry)) {
			Files.copy(in, out);
		}
	}

	/**
	 * The release type appears in the filename, not the directory, and a package
	 * carries all three side by side. Language-suffixed files exist too
	 * ({@code sct2_Description_Snapshot-en_...}), so this is a substring test on
	 * the base name rather than an exact match.
	 */
	static boolean keep(String entryName, String releaseType) {
		String base = new File(entryName).getName();
		if (!base.endsWith(".txt")) {
			return false;
		}
		// Mac archive noise, and the leading-dot files some packagers add.
		if (base.startsWith(".") || entryName.contains("__MACOSX")) {
			return false;
		}
		return base.contains(releaseType);
	}
}
