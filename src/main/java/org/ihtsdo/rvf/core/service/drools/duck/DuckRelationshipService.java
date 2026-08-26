package org.ihtsdo.rvf.core.service.drools.duck;

import org.ihtsdo.drools.service.RelationshipService;

/**
 * {@link RelationshipService} over DuckDB.
 *
 * <p>"Inbound stated" means an active row in the derived stated relationship
 * table whose destination is this concept. Matching {@code
 * SnomedDroolsComponentRepository.addRelationship}, these are NOT filtered by
 * GCI and NOT filtered by type - callers apply their own type test.
 *
 * <p>One difference worth recording: the incumbent does
 * {@code repository.getConcept(conceptId).getActiveInboundStatedRelationships()}
 * with no null check, so an unknown concept id throws NullPointerException.
 * Here it simply returns false. That is a deliberate divergence and the only
 * one in this class; if a rule depends on the exception it will show up in the
 * parity harness as a difference in violations rather than a crash.
 */
public class DuckRelationshipService implements RelationshipService {

	private final DuckDroolsDataset dataset;

	public DuckRelationshipService(DuckDroolsDataset dataset) {
		this.dataset = dataset;
	}

	@Override
	public boolean hasActiveInboundStatedRelationship(String conceptId) {
		return dataset.queryExists(
				"SELECT 1 FROM stated_relationship WHERE destination_id = ? AND active LIMIT 1",
				conceptId);
	}

	@Override
	public boolean hasActiveInboundStatedRelationship(String conceptId, String relationshipTypeId) {
		return dataset.queryExists(
				"SELECT 1 FROM stated_relationship "
				+ "WHERE destination_id = ? AND active AND type_id = ? LIMIT 1",
				conceptId, relationshipTypeId);
	}
}
