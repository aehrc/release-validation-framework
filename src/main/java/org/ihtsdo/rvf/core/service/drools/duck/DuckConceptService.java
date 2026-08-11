package org.ihtsdo.rvf.core.service.drools.duck;

import org.ihtsdo.drools.domain.Annotation;
import org.ihtsdo.drools.domain.Concept;
import org.ihtsdo.drools.domain.Constants;
import org.ihtsdo.drools.domain.Description;
import org.ihtsdo.drools.domain.OntologyAxiom;
import org.ihtsdo.drools.domain.Relationship;
import org.ihtsdo.drools.service.ConceptService;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link ConceptService} over DuckDB.
 *
 * <p>Ported method-by-method against {@code DroolsConceptService} at tag 5.7.0.
 * Where a condition looks incidental it is almost certainly not - see
 * {@link #isConceptModellingChanged} in particular - so each method carries the
 * reference behaviour it is reproducing.
 */
public class DuckConceptService implements ConceptService {

	private final DuckDroolsDataset dataset;

	/**
	 * Top level hierarchies are asked for once per description in some rules,
	 * and the answer cannot change during a run. The incumbent memoises this
	 * too, with double-checked locking.
	 */
	private volatile Set<String> topLevelHierarchies;

	public DuckConceptService(DuckDroolsDataset dataset) {
		this.dataset = dataset;
	}

	@Override
	public boolean isActive(String conceptId) {
		// Reference: concept != null && concept.isActive(). A concept absent
		// from the release is not active, rather than an error.
		return dataset.queryExists(
				"SELECT 1 FROM concept WHERE id = ? AND active = '1' LIMIT 1", conceptId);
	}

	@Override
	public boolean isInactiveConceptSameAs(String inactiveConceptId, String conceptId) {
		// Reference resolves REFSET_SAME_AS_ASSOCIATION through
		// Constants.historicalAssociationNames to a display name, keys the map
		// by that name, then checks membership. We compare on the refset id
		// directly, which is the same test without the indirection.
		return dataset.queryExists(
				"SELECT 1 FROM association_refset "
				+ "WHERE referencedComponentId = ? AND targetComponentId = ? "
				+ "  AND refsetId = ? AND active = '1' LIMIT 1",
				inactiveConceptId, conceptId, Constants.REFSET_SAME_AS_ASSOCIATION);
	}

	@Override
	public boolean isConceptModellingChanged(Concept concept) {
		// Reference, in order:
		//   1. any NON-GCI ontology axiom whose effectiveTime == currentEffectiveTime
		//   2. else any relationship with a NULL axiomId, characteristicType
		//      INFERRED, and effectiveTime == currentEffectiveTime
		// The null axiomId in (2) is what restricts it to genuine inferred
		// relationship rows rather than the stated rows derived from axioms.
		String et = dataset.getCurrentEffectiveTime();
		boolean axiomChanged = dataset.queryExists(
				"SELECT 1 FROM owl_refset o "
				+ "WHERE o.referencedComponentId = ? AND o.effectiveTime = ? "
				+ "  AND NOT EXISTS (SELECT 1 FROM stated_relationship s "
				+ "                  WHERE s.axiom_id = o.id AND s.axiom_gci) LIMIT 1",
				concept.getId(), et);
		if (axiomChanged) {
			return true;
		}
		return dataset.queryExists(
				"SELECT 1 FROM inferred_relationship "
				+ "WHERE sourceId = ? AND effectiveTime = ? AND characteristicTypeId = ? LIMIT 1",
				concept.getId(), et, Constants.INFERRED_RELATIONSHIP);
	}

	@Override
	public Concept findById(String conceptId) {
		try (PreparedStatement ps = dataset.getConnection().prepareStatement(
				"SELECT id, effectiveTime, active, moduleId, definitionStatusId "
				+ "FROM concept WHERE id = ? LIMIT 1")) {
			ps.setString(1, conceptId);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? conceptFrom(rs) : null;
			}
		} catch (SQLException e) {
			throw new IllegalStateException("findById failed for " + conceptId, e);
		}
	}

	@Override
	public Set<String> getAllTopLevelHierarchies() {
		Set<String> cached = topLevelHierarchies;
		if (cached != null) {
			return cached;
		}
		synchronized (this) {
			if (topLevelHierarchies != null) {
				return topLevelHierarchies;
			}
			// Reference: sourceIds of ROOT's active inbound STATED IS-A
			// relationships. Inbound sets are not GCI-filtered in the
			// incumbent, so they are not filtered here either.
			topLevelHierarchies = dataset.queryStrings(
					"SELECT DISTINCT source_id FROM stated_relationship "
					+ "WHERE destination_id = ? AND active AND type_id = ?",
					Constants.ROOT_CONCEPT, Constants.IS_A);
			return topLevelHierarchies;
		}
	}

	@Override
	public Set<String> findStatedAncestorsOfConcept(Concept concept) {
		if (concept == null || Constants.ROOT_CONCEPT.equals(concept.getId())) {
			return Collections.emptySet();
		}
		// Returns a mutable set: findTopLevelHierarchiesOfConcept calls
		// retainAll on the result, exactly as the incumbent does.
		return new HashSet<>(dataset.queryStrings(
				"SELECT ancestor_id FROM stated_ancestor WHERE concept_id = ?", concept.getId()));
	}

	@Override
	public Set<String> findTopLevelHierarchiesOfConcept(Concept concept) {
		Set<String> statedAncestors = findStatedAncestorsOfConcept(concept);
		statedAncestors.retainAll(getAllTopLevelHierarchies());
		return statedAncestors;
	}

	@Override
	public Set<String> findStatedAncestorsOfConcepts(List<String> conceptIds) {
		if (conceptIds == null || conceptIds.isEmpty()) {
			return Collections.emptySet();
		}
		// The incumbent loops one concept at a time; one IN query is the same
		// union. ROOT is excluded to match findStatedAncestorsOfConcept, which
		// returns empty for it.
		StringBuilder in = new StringBuilder();
		for (int i = 0; i < conceptIds.size(); i++) {
			in.append(i == 0 ? "?" : ",?");
		}
		String sql = "SELECT DISTINCT ancestor_id FROM stated_ancestor "
				+ "WHERE concept_id IN (" + in + ") AND concept_id <> ?";
		Object[] params = new Object[conceptIds.size() + 1];
		for (int i = 0; i < conceptIds.size(); i++) {
			params[i] = conceptIds.get(i);
		}
		params[conceptIds.size()] = Constants.ROOT_CONCEPT;
		return dataset.queryStrings(sql, params);
	}

	@Override
	public Set<String> findLanguageReferenceSetByModule(String moduleId) {
		// Reference walks two levels down from LANGUAGE_TYPE_CONCEPT via active
		// inbound stated IS-A, keeping any concept in the requested module at
		// either level. Expressed as a two-level join rather than a loop.
		return dataset.queryStrings(
				"WITH children AS ("
				+ "  SELECT DISTINCT sr.source_id AS id FROM stated_relationship sr "
				+ "  WHERE sr.destination_id = ? AND sr.active AND sr.type_id = ?"
				+ "), grandchildren AS ("
				+ "  SELECT DISTINCT sr.source_id AS id FROM stated_relationship sr "
				+ "  JOIN children c ON sr.destination_id = c.id "
				+ "  WHERE sr.active AND sr.type_id = ?"
				+ ") "
				+ "SELECT c.id FROM concept c "
				+ "WHERE c.moduleId = ? AND c.id IN (SELECT id FROM children UNION SELECT id FROM grandchildren)",
				Constants.LANGUAGE_TYPE_CONCEPT, Constants.IS_A, Constants.IS_A, moduleId);
	}

	/**
	 * Every concept in the release, as the set to validate.
	 *
	 * <p>Matches what the incumbent feeds the rule executor:
	 * {@code repository.getConcepts()} - all of them, active or not. The
	 * adapters are lazy, so this materialises one small object per concept
	 * rather than the whole graph.
	 */
	/**
	 * The concepts this edition actually authored in this cycle - the ones a
	 * nightly should validate.
	 *
	 * <p>A concept is in scope if it, or any of its descriptions, relationships
	 * or axioms, carries the edition's own effectiveTime. Content inherited from
	 * a dependency carries that dependency's older effectiveTime and is excluded,
	 * which is the whole point: on the AU daily build the RF2 Delta file is ~85%
	 * international content that IHTSDO has already validated.
	 *
	 * <p>Measured on AU 20260831: <b>293 concepts</b> in scope out of 722,210.
	 * International 20260501: 1,502 of 531,997.
	 *
	 * <p>The full snapshot stays queryable for context - ancestors, hierarchy,
	 * matching terms - because the rules need it. Only the validated set shrinks.
	 */
	public Collection<Concept> authoredConcepts(String editionEffectiveTime) {
		List<Concept> out = new ArrayList<>();
		try (PreparedStatement ps = dataset.getConnection().prepareStatement(
				"SELECT c.id, c.effectiveTime, c.active, c.moduleId, c.definitionStatusId "
				+ "FROM concept c WHERE c.id IN ("
				+ "  SELECT id          FROM concept              WHERE effectiveTime = ?"
				+ "  UNION SELECT conceptId FROM description      WHERE effectiveTime = ?"
				+ "  UNION SELECT sourceId  FROM inferred_relationship WHERE effectiveTime = ?"
				+ "  UNION SELECT referencedComponentId FROM owl_refset WHERE effectiveTime = ?)")) {
			for (int i = 1; i <= 4; i++) {
				ps.setString(i, editionEffectiveTime);
			}
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(conceptFrom(rs));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("authoredConcepts failed", e);
		}
		return out;
	}

	public Collection<Concept> allConcepts() {
		List<Concept> out = new ArrayList<>();
		try (PreparedStatement ps = dataset.getConnection().prepareStatement(
				"SELECT id, effectiveTime, active, moduleId, definitionStatusId FROM concept");
			 ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				out.add(conceptFrom(rs));
			}
		} catch (SQLException e) {
			throw new IllegalStateException("allConcepts failed", e);
		}
		return out;
	}

	// ------------------------------------------------------------------
	// Lazy resolution for the domain adapters
	// ------------------------------------------------------------------

	Collection<? extends Description> descriptionsOf(String conceptId) {
		List<Description> out = new ArrayList<>();
		Map<String, Map<String, String>> acceptability = acceptabilityFor(conceptId);
		try (PreparedStatement ps = dataset.getConnection().prepareStatement(
				"SELECT id, effectiveTime, active, moduleId, conceptId, languageCode, typeId, term, "
				+ "caseSignificanceId, is_text_definition FROM description WHERE conceptId = ?")) {
			ps.setString(1, conceptId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String id = rs.getString(1);
					String et = rs.getString(2);
					out.add(new DuckDomain.DuckDescription(id, et, "1".equals(rs.getString(3)),
							rs.getString(4), et != null && !et.isEmpty(), rs.getString(5), rs.getString(6),
							rs.getString(7), rs.getString(8), rs.getString(9), rs.getBoolean(10),
							acceptability.getOrDefault(id, Collections.emptyMap())));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("descriptionsOf failed for " + conceptId, e);
		}
		return out;
	}

	private Map<String, Map<String, String>> acceptabilityFor(String conceptId) {
		Map<String, Map<String, String>> out = new HashMap<>();
		try (PreparedStatement ps = dataset.getConnection().prepareStatement(
				"SELECT l.referencedComponentId, l.refsetId, l.acceptabilityId "
				+ "FROM language_refset l JOIN description d ON d.id = l.referencedComponentId "
				+ "WHERE d.conceptId = ? AND l.active = '1'")) {
			ps.setString(1, conceptId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
					   .put(rs.getString(2), rs.getString(3));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("acceptabilityFor failed for " + conceptId, e);
		}
		return out;
	}

	Collection<? extends Relationship> relationshipsOf(String conceptId) {
		List<Relationship> out = new ArrayList<>();
		// Both forms, as the incumbent's concept.getRelationships() holds:
		// inferred rows from the relationship file, and stated rows derived
		// from axioms. Callers distinguish them by characteristicTypeId.
		try (PreparedStatement ps = dataset.getConnection().prepareStatement(
				"SELECT id, effectiveTime, active, moduleId, sourceId, destinationId, typeId, "
				+ "       characteristicTypeId, relationshipGroup, NULL AS axiom_id, false AS gci, NULL AS cv "
				+ "FROM inferred_relationship WHERE sourceId = ? "
				+ "UNION ALL "
				// `id` is the synthesised composite identifier, not an SCTID - see
				// DuckDroolsDataset.compositeIdentifier. It must not be NULL:
				// RuleExecutor.execute aborts the whole run if any relationship's
				// id is null or empty, which is how this was found.
				+ "SELECT id, effective_time, CASE WHEN active THEN '1' ELSE '0' END, module_id, "
				+ "       source_id, destination_id, type_id, ?, "
				+ "       CAST(relationship_group AS VARCHAR), axiom_id, axiom_gci, concrete_value "
				+ "FROM stated_relationship WHERE source_id = ?")) {
			ps.setString(1, conceptId);
			ps.setString(2, Constants.STATED_RELATIONSHIP);
			ps.setString(3, conceptId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String et = rs.getString(2);
					String group = rs.getString(9);
					out.add(new DuckDomain.DuckRelationship(rs.getString(1), et,
							"1".equals(rs.getString(3)), rs.getString(4), et != null && !et.isEmpty(),
							rs.getString(10), rs.getBoolean(11), rs.getString(5), rs.getString(6),
							rs.getString(7), rs.getString(8), rs.getString(12),
							group == null || group.isEmpty() ? 0 : Integer.parseInt(group)));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("relationshipsOf failed for " + conceptId, e);
		}
		return out;
	}

	/**
	 * Component annotations for a concept, from the Component Annotation String
	 * Value refset. Backs {@code Concept.getAnnotations()}, added in
	 * snomed-drools 6.0.0.
	 */
	Collection<? extends Annotation> annotationsOf(String conceptId) {
		List<Annotation> out = new ArrayList<>();
		try (PreparedStatement ps = dataset.getConnection().prepareStatement(
				"SELECT id, effectiveTime, active, moduleId, languageDialectCode, typeId, value "
				+ "FROM annotation_refset WHERE referencedComponentId = ?")) {
			ps.setString(1, conceptId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String et = rs.getString(2);
					out.add(new DuckDomain.DuckAnnotation(rs.getString(1), et,
							"1".equals(rs.getString(3)), rs.getString(4),
							et != null && !et.isEmpty(),
							conceptId, rs.getString(5), rs.getString(6), rs.getString(7)));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("annotationsOf failed for " + conceptId, e);
		}
		return out;
	}

	Collection<? extends OntologyAxiom> axiomsOf(String conceptId) {
		List<OntologyAxiom> out = new ArrayList<>();
		try (PreparedStatement ps = dataset.getConnection().prepareStatement(
				"SELECT o.id, o.effectiveTime, o.active, o.moduleId, o.referencedComponentId, o.owlExpression, "
				+ "  COALESCE((SELECT bool_or(s.axiom_gci) FROM stated_relationship s WHERE s.axiom_id = o.id), false) "
				+ "FROM owl_refset o WHERE o.referencedComponentId = ?")) {
			ps.setString(1, conceptId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String et = rs.getString(2);
					out.add(new DuckDomain.DuckOntologyAxiom(rs.getString(1), et, "1".equals(rs.getString(3)),
							rs.getString(4), et != null && !et.isEmpty(), rs.getString(5), rs.getString(6),
							null, false, rs.getBoolean(7)));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("axiomsOf failed for " + conceptId, e);
		}
		return out;
	}

	Map<String, Set<String>> associationTargetsOf(String conceptId) {
		Map<String, Set<String>> out = new HashMap<>();
		try (PreparedStatement ps = dataset.getConnection().prepareStatement(
				"SELECT refsetId, targetComponentId FROM association_refset "
				+ "WHERE referencedComponentId = ? AND active = '1'")) {
			ps.setString(1, conceptId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String refsetId = rs.getString(1);
					// The incumbent keys by display name where one exists,
					// falling back to the raw refset id.
					String key = Constants.historicalAssociationNames.getOrDefault(refsetId, refsetId);
					out.computeIfAbsent(key, k -> new HashSet<>()).add(rs.getString(2));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("associationTargetsOf failed for " + conceptId, e);
		}
		return out;
	}

	private Concept conceptFrom(ResultSet rs) throws SQLException {
		String et = rs.getString(2);
		return new DuckDomain.DuckConcept(rs.getString(1), et, "1".equals(rs.getString(3)),
				rs.getString(4), et != null && !et.isEmpty(), rs.getString(5), this);
	}
}
