package org.ihtsdo.rvf.core.service;

import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

/**
 * The published releases this instance has been given, and can validate against.
 *
 * <p>Exists so that "keep the last release so tonight's export can be compared
 * against it" is a capability of RVF rather than a property of MySQL. Under the
 * MySQL engine a kept release is a loaded schema, {@code rvf_{product}_{version}},
 * and the catalogue is that engine's schema list. Under DuckDB there are no
 * persistent schemas - a release is materialised for a run and thrown away - so a
 * kept release is the RF2 package itself, held in {@code rvf.release.storage} and
 * addressed by filename.
 *
 * <p>The filename is not an arbitrary choice: it is already the contract.
 * {@code ReleaseAcquisitionService.downloadPreviousReleaseFromFallbackSource}
 * resolves {@code previousRelease} by reading exactly that name from release
 * storage, so a name this catalogue reports is a name a caller can pass straight
 * back as {@code previousRelease}.
 */
public interface ReleaseCatalogue {

	/**
	 * Every release this instance can validate against, by the name a caller
	 * should pass as {@code previousRelease} or {@code dependencyRelease}.
	 */
	Set<String> names();

	/** Whether one particular name is available. */
	boolean contains(String name);

	/**
	 * Keeps a published release for later comparison.
	 *
	 * @param product the short product name, as the MySQL naming convention uses
	 * @param version the release date, likewise
	 * @return the name under which it was kept, which is what to pass as
	 *         {@code previousRelease}. The two engines answer differently and
	 *         deliberately: a schema name, or a filename.
	 */
	String store(MultipartFile file, String product, String version) throws Exception;

	/** Whether {@link #store} does anything on this engine. */
	default boolean canStore() {
		return true;
	}
}
