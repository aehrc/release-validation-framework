package org.ihtsdo.drools.validator.rf2;

import org.ihtsdo.drools.validator.rf2.service.DroolsConceptService;
import org.ihtsdo.drools.validator.rf2.service.DroolsRelationshipService;
import org.ihtsdo.otf.snomedboot.ReleaseImportException;
import org.ihtsdo.otf.snomedboot.ReleaseImporter;
import org.ihtsdo.otf.snomedboot.domain.Concept;
import org.ihtsdo.otf.snomedboot.factory.implementation.standard.ComponentStore;

import java.util.Map;
import java.util.Set;

/**
 * Test-only access to the incumbent's component repository.
 *
 * <p>Deliberately in {@code org.ihtsdo.drools.validator.rf2}:
 * {@code SnomedDroolsComponentFactory}'s constructor is package-private, and
 * this reproduces {@code DroolsRF2Validator.loadComponentsFromRF2}, which is
 * private. Same package, so the access is compile-checked rather than
 * reflective - if upstream changes either signature this stops compiling,
 * which is the outcome we want from a harness that exists to detect drift.
 *
 * <p>Test scope only. Nothing here ships in the image.
 *
 * <p>End-to-end parity does NOT need this - both
 * {@code DroolsRF2Validator.validateRF2Files} and
 * {@code RuleExecutor.execute} are public. This exists for the second level of
 * the harness: diffing individual service methods to localise a difference
 * once end-to-end says one exists, and specifically to settle whether
 * snomed-boot's stated-ancestor computation includes GCI-derived parents.
 */
public final class IncumbentRepositoryAccess {

	private IncumbentRepositoryAccess() {
	}

	public static SnomedDroolsComponentRepository load(Set<String> extractedRF2FilesDirectories,
													  String currentEffectiveTime) throws ReleaseImportException {
		ReleaseImporter releaseImporter = new ReleaseImporter();
		SnomedDroolsComponentRepository repository = new SnomedDroolsComponentRepository();
		ComponentStore componentStore = new ComponentStore();
		SnomedDroolsComponentFactory componentFactory =
				new SnomedDroolsComponentFactory(componentStore, repository, currentEffectiveTime, null);

		boolean loadDelta = RF2ReleaseFilesUtil.anyDeltaFilesPresent(extractedRF2FilesDirectories);
		if (loadDelta) {
			releaseImporter.loadEffectiveSnapshotAndDeltaReleaseFiles(
					extractedRF2FilesDirectories, DroolsRF2Validator.LOADING_PROFILE, componentFactory, false);
		} else {
			releaseImporter.loadEffectiveSnapshotReleaseFiles(
					extractedRF2FilesDirectories, DroolsRF2Validator.LOADING_PROFILE, componentFactory, false);
		}

		// Stated ancestors are computed by snomed-boot, not by snomed-drools,
		// and are copied across afterwards. Reproducing this is the whole point
		// of the class: it is the step whose GCI treatment we cannot determine
		// by reading snomed-drools alone.
		final Map<Long, ? extends Concept> conceptMap = componentFactory.getComponentStore().getConcepts();
		repository.getConcepts().forEach(item -> {
			Concept concept = conceptMap.get(Long.parseLong(item.getId()));
			if (concept != null) {
				item.setStatedAncestorIds(concept.getStatedAncestorIds());
			}
		});
		return repository;
	}

	public static DroolsConceptService conceptService(SnomedDroolsComponentRepository repository,
													 String currentEffectiveTime) {
		return new DroolsConceptService(repository, currentEffectiveTime);
	}

	public static DroolsRelationshipService relationshipService(SnomedDroolsComponentRepository repository) {
		return new DroolsRelationshipService(repository);
	}
}
