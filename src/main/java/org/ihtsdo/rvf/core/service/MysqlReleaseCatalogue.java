package org.ihtsdo.rvf.core.service;

import org.ihtsdo.rvf.config.ConditionalOnMysqlEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Collections;
import java.util.Set;

/**
 * {@link ReleaseCatalogue} over loaded MySQL schemas - today's behaviour,
 * unchanged.
 *
 * <p>A "kept release" here is a schema named {@code rvf_{product}_{version}}
 * holding the release's RF2 rows, and the catalogue is
 * {@code ReleaseDataManager}'s in-memory set of schema names, populated at
 * startup from the schemas that exist. Nothing about that changes; this class
 * only gives it an interface the REST layer can depend on without depending on
 * the engine.
 */
@Service
@ConditionalOnMysqlEngine
public class MysqlReleaseCatalogue implements ReleaseCatalogue {

	private final ReleaseDataManager releaseDataManager;

	@Autowired
	public MysqlReleaseCatalogue(ReleaseDataManager releaseDataManager) {
		this.releaseDataManager = releaseDataManager;
	}

	@Override
	public Set<String> names() {
		Set<String> names = releaseDataManager.getAllKnownReleases();
		return names == null ? Set.of() : names;
	}

	@Override
	public boolean contains(String name) {
		return releaseDataManager.isKnownRelease(name);
	}

	@Override
	public String store(MultipartFile file, String product, String version) throws Exception {
		releaseDataManager.uploadPublishedReleaseData(file.getInputStream(),
				file.getOriginalFilename(), product, version, Collections.emptyList());
		return releaseDataManager.getRVFVersion(product, version);
	}
}
