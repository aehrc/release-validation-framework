package org.ihtsdo.rvf.core.service.drools.duck;

import org.ihtsdo.drools.domain.Annotation;
import org.ihtsdo.drools.domain.Concept;
import org.ihtsdo.drools.domain.Constants;
import org.ihtsdo.drools.domain.Description;
import org.ihtsdo.drools.domain.OntologyAxiom;
import org.ihtsdo.drools.domain.Relationship;
import org.ihtsdo.drools.service.ConceptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
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

	private static final Logger LOGGER = LoggerFactory.getLogger(DuckConceptService.class);

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

	/**
	 * Memo for {@link #isActive}. Rules ask it of every destination of every
	 * relationship, so the same handful of ids are asked thousands of times -
	 * sampling a scoped run put this among the top costs.
	 */
	private final Map<String, Boolean> activeById = new ConcurrentHashMap<>();

	@Override
	public boolean isActive(String conceptId) {
		// Reference: concept != null && concept.isActive(). A concept absent
		// from the release is not active, rather than an error.
		return activeById.computeIfAbsent(conceptId, id -> dataset.queryExists(
				"SELECT 1 FROM concept WHERE id = ? AND active = '1' LIMIT 1", id));
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

	/**
	 * Memos for the two accessors the rule executor hammers.
	 *
	 * <p>Drools' domain API is object-graph navigation - findById per referenced
	 * component in checkComponentsIntegrity, getDescriptions per concept in
	 * assertComponentIdsPresent - and every one of those was a JDBC round trip
	 * for an id already fetched. That is the wrong shape for a columnar engine
	 * however well indexed, so the ids are held once instead of re-queried.
	 *
	 * <p>Bounded by the SCOPE, not by the edition: on the MDRS-authored scope
	 * this is a few hundred entries. Running the full snapshot through it would
	 * hold the whole edition, which is the in-heap backend again - so scope
	 * first, then serve.
	 *
	 * <p>Concurrent because RuleExecutor runs the rules on a worker pool.
	 */
	private final Map<String, Optional<Concept>> byId = new ConcurrentHashMap<>();
	private final Map<String, Collection<? extends Description>> descriptions =
			new ConcurrentHashMap<>();

	@Override
	public Concept findById(String conceptId) {
		return byId.computeIfAbsent(conceptId, id -> Optional.ofNullable(loadById(id))).orElse(null);
	}

	private Concept loadById(String conceptId) {
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

	/**
	 * Memo for {@link #findStatedAncestorsOfConcept}. A recursive CTE per
	 * concept, and the rules ask repeatedly for the same concepts.
	 */
	private final Map<String, Set<String>> statedAncestors = new ConcurrentHashMap<>();

	@Override
	public Set<String> findStatedAncestorsOfConcept(Concept concept) {
		return statedAncestors.computeIfAbsent(concept.getId(),
				id -> loadStatedAncestorsOfConcept(concept));
	}

	private Set<String> loadStatedAncestorsOfConcept(Concept concept) {
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

	// ------------------------------------------------------------------
	// Bulk prefetch for a validation scope
	// ------------------------------------------------------------------

	/**
	 * The scope's object graph, loaded up front with one query per relation.
	 *
	 * <p>{@code scope} is the authority on WHICH concepts were prefetched, and it
	 * is not the same thing as the map key sets. A concept in scope with no
	 * descriptions must answer "no descriptions" from memory; a concept OUTSIDE
	 * the scope must fall through to a query, because the rules legitimately
	 * navigate beyond what is being validated - ancestors, hierarchy siblings,
	 * matching terms elsewhere in the release. Keying only on map membership
	 * would conflate those two and silently report an out-of-scope concept as
	 * having no descriptions.
	 */
	private record Prefetched(Set<String> scope,
			Map<String, List<Description>> descriptions,
			Map<String, List<Relationship>> relationships,
			Map<String, List<OntologyAxiom>> axioms,
			Map<String, List<Annotation>> annotations,
			Map<String, Map<String, Set<String>>> associationTargets) {

		<T> Collection<T> serve(Map<String, List<T>> from, String conceptId) {
			List<T> hit = from.get(conceptId);
			return hit != null ? hit : List.of();
		}
	}

	private volatile Prefetched prefetched;

	/**
	 * Loads everything the rules will navigate for {@code scope}, in one query
	 * per relation instead of one per concept per accessor.
	 *
	 * <p>This is the whole point of scoping first. Drools' domain API is
	 * object-graph navigation - {@code getDescriptions()} per concept,
	 * {@code findById} per referenced component - so against a database every
	 * accessor was a round trip: 542 concepts cost 75 s even indexed and
	 * memoised. DuckDB is a columnar engine; it wants six bulk scans, not
	 * thousands of point lookups. Derive here, serve from memory there.
	 *
	 * <p>Safe to call once per scope. Calling it for the full snapshot would
	 * hold the entire edition in heap, which is the in-heap backend again with
	 * extra steps - scope first.
	 */
	public void prefetch(Collection<Concept> scope) {
		Set<String> ids = new HashSet<>();
		for (Concept c : scope) {
			ids.add(c.getId());
		}
		if (ids.isEmpty()) {
			prefetched = new Prefetched(Set.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
			return;
		}
		long t0 = System.currentTimeMillis();
		dataset.materialiseScope(ids);
		Map<String, Map<String, String>> acceptability = bulkAcceptability();
		Prefetched p = new Prefetched(ids,
				bulkDescriptions(acceptability),
				bulkRelationships(),
				bulkAxioms(),
				bulkAnnotations(),
				bulkAssociationTargets());
		prefetched = p;
		LOGGER.info("prefetched {} concepts in {} ms: {} descriptions, {} relationships, "
				+ "{} axioms, {} annotations", ids.size(), System.currentTimeMillis() - t0,
				p.descriptions().values().stream().mapToInt(List::size).sum(),
				p.relationships().values().stream().mapToInt(List::size).sum(),
				p.axioms().values().stream().mapToInt(List::size).sum(),
				p.annotations().values().stream().mapToInt(List::size).sum());
	}

	/**
	 * Drops everything held for the current scope, so the next batch starts
	 * from a flat heap.
	 *
	 * <p>Needed because the per-scope memos are what make a batch fast and what
	 * make a whole edition impossible. Measured: prefetching all 722,404
	 * concepts in one call loads 2,265,530 descriptions and 7,581,616
	 * relationships, pins an 8 GB heap at 7.35 GB and puts the JVM into
	 * near-continuous GC - a 36 second concurrent mark cycle - which is 70x
	 * slower than the in-heap backend doing the same work in the same heap. The
	 * fix is not a bigger heap; it is to hold one batch at a time.
	 *
	 * <p>{@link #activeById} deliberately SURVIVES. It answers a question about
	 * the release rather than about the scope, it is asked of every destination
	 * of every relationship, and one boolean per concept is bounded by the
	 * edition at a few tens of MB. The other four are per-concept object graphs
	 * and are what actually grow without limit.
	 */
	public void releaseScope() {
		prefetched = null;
		byId.clear();
		descriptions.clear();
		statedAncestors.clear();
	}

	/** Acceptability for every description of every in-scope concept, in one query. */
	private Map<String, Map<String, String>> bulkAcceptability() {
		Map<String, Map<String, String>> out = new HashMap<>();
		dataset.eachRow("SELECT l.referencedComponentId, l.refsetId, l.acceptabilityId "
				+ "FROM language_refset l JOIN description d ON d.id = l.referencedComponentId "
				+ "JOIN " + DuckDroolsDataset.SCOPE_TABLE + " s ON s.id = d.conceptId "
				+ "WHERE l.active = '1'",
				rs -> out.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
						.put(rs.getString(2), rs.getString(3)));
		return out;
	}

	private Map<String, List<Description>> bulkDescriptions(
			Map<String, Map<String, String>> acceptability) {
		Map<String, List<Description>> out = new HashMap<>();
		dataset.eachRow(DESCRIPTION_SELECT + " JOIN " + DuckDroolsDataset.SCOPE_TABLE
				+ " s ON s.id = d.conceptId",
				rs -> out.computeIfAbsent(rs.getString(5), k -> new ArrayList<>())
						.add(descriptionFrom(rs, acceptability)));
		return out;
	}

	private Map<String, List<Relationship>> bulkRelationships() {
		Map<String, List<Relationship>> out = new HashMap<>();
		// Same UNION as relationshipsOf, joined to the scope rather than bound
		// to one id. Column order must stay identical - relationshipFrom reads
		// by position.
		dataset.eachRow("SELECT r.id, r.effectiveTime, r.active, r.moduleId, r.sourceId, "
				+ "       r.destinationId, r.typeId, r.characteristicTypeId, r.relationshipGroup, "
				+ "       NULL AS axiom_id, false AS gci, NULL AS cv "
				+ "FROM inferred_relationship r "
				+ "JOIN " + DuckDroolsDataset.SCOPE_TABLE + " s ON s.id = r.sourceId "
				+ "UNION ALL "
				+ "SELECT sr.id, sr.effective_time, CASE WHEN sr.active THEN '1' ELSE '0' END, "
				+ "       sr.module_id, sr.source_id, sr.destination_id, sr.type_id, '"
				+ Constants.STATED_RELATIONSHIP + "', "
				+ "       CAST(sr.relationship_group AS VARCHAR), sr.axiom_id, sr.axiom_gci, "
				+ "       sr.concrete_value "
				+ "FROM stated_relationship sr "
				+ "JOIN " + DuckDroolsDataset.SCOPE_TABLE + " s ON s.id = sr.source_id",
				rs -> out.computeIfAbsent(rs.getString(5), k -> new ArrayList<>())
						.add(relationshipFrom(rs)));
		return out;
	}

	private Map<String, List<OntologyAxiom>> bulkAxioms() {
		Map<String, List<OntologyAxiom>> out = new HashMap<>();
		dataset.eachRow("SELECT o.id, o.effectiveTime, o.active, o.moduleId, o.referencedComponentId, "
				+ "  o.owlExpression, "
				+ "  COALESCE((SELECT bool_or(x.axiom_gci) FROM stated_relationship x "
				+ "            WHERE x.axiom_id = o.id), false) "
				+ "FROM owl_refset o "
				+ "JOIN " + DuckDroolsDataset.SCOPE_TABLE + " s ON s.id = o.referencedComponentId",
				rs -> {
					String et = rs.getString(2);
					out.computeIfAbsent(rs.getString(5), k -> new ArrayList<>())
							.add(new DuckDomain.DuckOntologyAxiom(rs.getString(1), et,
									"1".equals(rs.getString(3)), rs.getString(4),
									et != null && !et.isEmpty(), rs.getString(5), rs.getString(6),
									null, false, rs.getBoolean(7)));
				});
		return out;
	}

	private Map<String, List<Annotation>> bulkAnnotations() {
		Map<String, List<Annotation>> out = new HashMap<>();
		dataset.eachRow("SELECT a.id, a.effectiveTime, a.active, a.moduleId, a.languageDialectCode, "
				+ "  a.typeId, a.value, a.referencedComponentId "
				+ "FROM annotation_refset a "
				+ "JOIN " + DuckDroolsDataset.SCOPE_TABLE + " s ON s.id = a.referencedComponentId",
				rs -> {
					String et = rs.getString(2);
					String concept = rs.getString(8);
					out.computeIfAbsent(concept, k -> new ArrayList<>())
							.add(new DuckDomain.DuckAnnotation(rs.getString(1), et,
									"1".equals(rs.getString(3)), rs.getString(4),
									et != null && !et.isEmpty(), concept, rs.getString(5),
									rs.getString(6), rs.getString(7)));
				});
		return out;
	}

	private Map<String, Map<String, Set<String>>> bulkAssociationTargets() {
		Map<String, Map<String, Set<String>>> out = new HashMap<>();
		dataset.eachRow("SELECT a.referencedComponentId, a.refsetId, a.targetComponentId "
				+ "FROM association_refset a "
				+ "JOIN " + DuckDroolsDataset.SCOPE_TABLE + " s ON s.id = a.referencedComponentId "
				+ "WHERE a.active = '1'",
				rs -> {
					String refsetId = rs.getString(2);
					String key = Constants.historicalAssociationNames.getOrDefault(refsetId, refsetId);
					out.computeIfAbsent(rs.getString(1), k -> new HashMap<>())
							.computeIfAbsent(key, k -> new HashSet<>())
							.add(rs.getString(3));
				});
		return out;
	}

	Collection<? extends Description> descriptionsOf(String conceptId) {
		Prefetched p = prefetched;
		if (p != null && p.scope().contains(conceptId)) {
			return p.serve(p.descriptions(), conceptId);
		}
		return descriptions.computeIfAbsent(conceptId, this::loadDescriptionsOf);
	}

	private Collection<? extends Description> loadDescriptionsOf(String conceptId) {
		List<Description> out = new ArrayList<>();
		Map<String, Map<String, String>> acceptability = acceptabilityFor(conceptId);
		try (PreparedStatement ps = dataset.getConnection().prepareStatement(
				DESCRIPTION_SELECT + " WHERE d.conceptId = ?")) {
			ps.setString(1, conceptId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.add(descriptionFrom(rs, acceptability));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("descriptionsOf failed for " + conceptId, e);
		}
		return out;
	}

	/**
	 * Columns qualified and the table aliased, so the same string works both
	 * standalone and joined to the scope table - an unqualified {@code id} is
	 * ambiguous the moment anything is joined to it.
	 */
	static final String DESCRIPTION_SELECT =
			"SELECT d.id, d.effectiveTime, d.active, d.moduleId, d.conceptId, d.languageCode, "
			+ "d.typeId, d.term, d.caseSignificanceId, d.is_text_definition FROM description d";

	/**
	 * One row of {@link #DESCRIPTION_SELECT} as a domain object.
	 *
	 * <p>Shared by the per-concept path and the bulk prefetch precisely so the
	 * two cannot construct differently. A prefetch that maps rows even slightly
	 * differently from the lazy path would change FINDINGS, not just timing,
	 * and the difference would be invisible in any timing comparison.
	 */
	private Description descriptionFrom(ResultSet rs, Map<String, Map<String, String>> acceptability)
			throws SQLException {
		String id = rs.getString(1);
		String et = rs.getString(2);
		return new DuckDomain.DuckDescription(id, et, "1".equals(rs.getString(3)),
				rs.getString(4), et != null && !et.isEmpty(), rs.getString(5), rs.getString(6),
				rs.getString(7), rs.getString(8), rs.getString(9), rs.getBoolean(10),
				acceptability.getOrDefault(id, Collections.emptyMap()));
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
		Prefetched p = prefetched;
		if (p != null && p.scope().contains(conceptId)) {
			return p.serve(p.relationships(), conceptId);
		}

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
					out.add(relationshipFrom(rs));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("relationshipsOf failed for " + conceptId, e);
		}
		return out;
	}

	/** One relationship row as a domain object. Shared with the bulk prefetch. */
	private Relationship relationshipFrom(ResultSet rs) throws SQLException {
		String et = rs.getString(2);
		String group = rs.getString(9);
		return new DuckDomain.DuckRelationship(rs.getString(1), et,
				"1".equals(rs.getString(3)), rs.getString(4), et != null && !et.isEmpty(),
				rs.getString(10), rs.getBoolean(11), rs.getString(5), rs.getString(6),
				rs.getString(7), rs.getString(8), rs.getString(12),
				group == null || group.isEmpty() ? 0 : Integer.parseInt(group));
	}

	/**
	 * Component annotations for a concept, from the Component Annotation String
	 * Value refset. Backs {@code Concept.getAnnotations()}, added in
	 * snomed-drools 6.0.0.
	 */
	Collection<? extends Annotation> annotationsOf(String conceptId) {
		Prefetched p = prefetched;
		if (p != null && p.scope().contains(conceptId)) {
			return p.serve(p.annotations(), conceptId);
		}

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
		Prefetched p = prefetched;
		if (p != null && p.scope().contains(conceptId)) {
			return p.serve(p.axioms(), conceptId);
		}

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
		Prefetched p = prefetched;
		if (p != null && p.scope().contains(conceptId)) {
			return p.associationTargets().getOrDefault(conceptId, Map.of());
		}
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
