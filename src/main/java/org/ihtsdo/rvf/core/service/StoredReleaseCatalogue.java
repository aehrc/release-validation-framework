package org.ihtsdo.rvf.core.service;

import org.ihtsdo.otf.resourcemanager.ResourceManager;
import org.ihtsdo.rvf.config.ExecutionEngine;
import org.ihtsdo.rvf.core.service.config.ValidationReleaseStorageConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.TreeSet;

/**
 * {@link ReleaseCatalogue} over the RF2 packages in {@code rvf.release.storage}.
 *
 * <p>This is what lets a nightly work under DuckDB. That engine keeps no
 * persistent schemas - a release is materialised into a per-run database file and
 * deleted with it - so "the last release" has to be kept as the package itself.
 * The packages are already the contract:
 * {@code ReleaseAcquisitionService.downloadPreviousReleaseFromFallbackSource}
 * resolves {@code previousRelease} by reading that exact filename from this
 * store, so every name listed here can be passed straight back as
 * {@code previousRelease}.
 *
 * <p>Why the package and not a materialised DuckDB file: the package is the
 * system of record and a materialised file is a derivative that can be rebuilt
 * from it. Measured on the AU edition, the package is 853MB and its materialised
 * form is 1.57GB, so the derivative is also the larger of the two. Caching that
 * derivative is worthwhile and is a separate concern from keeping the release.
 */
@Service
@ConditionalOnProperty(name = ExecutionEngine.PROPERTY, havingValue = ExecutionEngine.DUCKDB)
public class StoredReleaseCatalogue implements ReleaseCatalogue {

	private static final Logger LOGGER = LoggerFactory.getLogger(StoredReleaseCatalogue.class);

	private static final String ZIP = ".zip";

	private final ValidationReleaseStorageConfig releaseStorageConfig;
	private final ResourceLoader cloudResourceLoader;
	private ResourceManager releaseStore;

	@Autowired
	public StoredReleaseCatalogue(ValidationReleaseStorageConfig releaseStorageConfig,
			ResourceLoader cloudResourceLoader) {
		this.releaseStorageConfig = releaseStorageConfig;
		this.cloudResourceLoader = cloudResourceLoader;
	}

	@PostConstruct
	public void init() {
		releaseStore = new ResourceManager(releaseStorageConfig, cloudResourceLoader);
	}

	/** Test seam: a store built directly, without a Spring context. */
	StoredReleaseCatalogue(ResourceManager releaseStore) {
		this.releaseStorageConfig = null;
		this.cloudResourceLoader = null;
		this.releaseStore = releaseStore;
	}

	@Override
	public Set<String> names() {
		try {
			// Sorted, because this is a list a human reads to choose a previous
			// release, and release names sort chronologically by construction.
			return new TreeSet<>(releaseStore.listFilenamesBySuffix(ZIP));
		} catch (Exception e) {
			// An unconfigured or absent store is "no releases kept", not a 500:
			// a fresh deployment has none and should say so plainly. Exception
			// rather than IOException deliberately - ResourceManager.listFilenames
			// throws NullPointerException when the local directory does not
			// exist, because File.listFiles() returns null and it dereferences
			// the result. A fresh deployment is exactly that case.
			LOGGER.warn("Could not list the release store, reporting no kept releases: {}", e.toString());
			return Set.of();
		}
	}

	@Override
	public boolean contains(String name) {
		if (name == null || name.isBlank()) {
			return false;
		}
		try {
			return releaseStore.doesObjectExist(name);
		} catch (Exception e) {
			LOGGER.warn("Could not check the release store for '{}': {}", name, e.toString());
			return false;
		}
	}

	@Override
	public String store(MultipartFile file, String product, String version) throws IOException {
		String filename = file.getOriginalFilename();
		if (filename == null || filename.isBlank()) {
			throw new IOException("The uploaded release has no filename, and the filename is the name"
					+ " a validation will later use to ask for it");
		}
		try (InputStream in = file.getInputStream()) {
			releaseStore.writeResource(filename, in);
		}
		// product/version are the MySQL schema-naming convention and have no
		// meaning here. Say so rather than silently accepting them, because a
		// caller who passes them is expecting rvf_{product}_{version} back.
		LOGGER.info("Kept release '{}' (product={} version={} name a MySQL schema, not a stored package)",
				filename, product, version);
		return filename;
	}
}
