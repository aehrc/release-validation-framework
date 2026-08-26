package org.ihtsdo.rvf.core.service.drools.duck;

import org.ihtsdo.drools.domain.Concept;
import org.ihtsdo.drools.domain.Constants;
import org.ihtsdo.drools.domain.Description;
import org.ihtsdo.drools.domain.Relationship;
import org.ihtsdo.drools.helper.DescriptionHelper;
import org.ihtsdo.drools.service.DescriptionService;
import org.ihtsdo.drools.service.TestResourceProvider;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * {@link DescriptionService} over DuckDB.
 *
 * <p>Four of the nine methods never touch the component repository at all -
 * they are pure {@link TestResourceProvider} and {@link DescriptionHelper}
 * lookups. Those are delegated verbatim rather than reimplemented, so they
 * cannot drift from the incumbent.
 *
 * <h2>The term index is exact match</h2>
 *
 * {@code DroolsDescriptionIndex} builds an in-memory Lucene index over every
 * description term and queries it with a {@code TermQuery}. It looks like text
 * search and is not: the fields are {@code StringField}, which Lucene does not
 * analyse, so the {@code StandardAnalyzer} handed to the {@code IndexWriter} is
 * never applied to them. The lookup is therefore exact, case-sensitive,
 * whole-string equality - which is {@code WHERE term = ?}, and lets the Lucene
 * index over ~1.5M descriptions go away entirely.
 */
public class DuckDescriptionService implements DescriptionService {

	private static final String FULLY_SPECIFIED_NAME = "900000000000003001";
	private static final String PREFERRED_ACCEPTABILITY = "900000000000548007";

	private final DuckDroolsDataset dataset;
	private final DuckConceptService conceptService;
	private final TestResourceProvider testResourceProvider;

	public DuckDescriptionService(DuckDroolsDataset dataset, DuckConceptService conceptService,
								  TestResourceProvider testResourceProvider) {
		this.dataset = dataset;
		this.conceptService = conceptService;
		this.testResourceProvider = testResourceProvider;
	}

	@Override
	public Set<String> getFSNs(Set<String> conceptIds, String... languageRefsetIds) {
		Set<String> fsns = new HashSet<>();
		if (conceptIds == null || conceptIds.isEmpty()) {
			return fsns;
		}
		StringBuilder in = new StringBuilder();
		for (int i = 0; i < conceptIds.size(); i++) {
			in.append(i == 0 ? "?" : ",?");
		}
		boolean filterByRefset = languageRefsetIds != null && languageRefsetIds.length > 0;
		StringBuilder sql = new StringBuilder(
				"SELECT DISTINCT d.term FROM description d "
				+ "WHERE d.conceptId IN (" + in + ") AND d.active = '1' AND d.typeId = ?");
		if (filterByRefset) {
			// Reference requires PREFERRED acceptability in at least one of the
			// supplied refsets; with none supplied, every active FSN counts.
			sql.append(" AND EXISTS (SELECT 1 FROM language_refset l "
					+ "WHERE l.referencedComponentId = d.id AND l.active = '1' "
					+ "  AND l.acceptabilityId = ? AND l.refsetId IN (");
			for (int i = 0; i < languageRefsetIds.length; i++) {
				sql.append(i == 0 ? "?" : ",?");
			}
			sql.append("))");
		}
		Object[] params = new Object[conceptIds.size() + 1 + (filterByRefset ? 1 + languageRefsetIds.length : 0)];
		int p = 0;
		for (String conceptId : conceptIds) {
			params[p++] = conceptId;
		}
		params[p++] = FULLY_SPECIFIED_NAME;
		if (filterByRefset) {
			params[p++] = PREFERRED_ACCEPTABILITY;
			for (String refsetId : languageRefsetIds) {
				params[p++] = refsetId;
			}
		}
		fsns.addAll(dataset.queryStrings(sql.toString(), params));
		return fsns;
	}

	@Override
	public Set<Description> findActiveDescriptionByExactTerm(String exactTerm) {
		return findByExactTerm(exactTerm, true);
	}

	@Override
	public Set<Description> findInactiveDescriptionByExactTerm(String exactTerm) {
		return findByExactTerm(exactTerm, false);
	}

	/**
	 * Memo for {@link #findByExactTerm}, keyed by term and activity.
	 *
	 * <p>Sampling a scoped run put this method at the top by a wide margin, even
	 * with an index on {@code description(term)}: the rules ask it of every
	 * description they look at, and {@code findMatchingDescriptionInHierarchy}
	 * asks it again. It cannot be scoped away - the question is "does this term
	 * exist ANYWHERE else in the release" - but the release does not change
	 * during a run, so the answer is a pure function of the term.
	 */
	private final Map<String, Set<Description>> byExactTerm = new ConcurrentHashMap<>();

	private Set<Description> findByExactTerm(String exactTerm, boolean active) {
		if (exactTerm == null || exactTerm.trim().isEmpty()) {
			return Collections.emptySet();
		}
		return byExactTerm.computeIfAbsent((active ? "1\u0000" : "0\u0000") + exactTerm,
				k -> loadByExactTerm(exactTerm, active));
	}

	private Set<Description> loadByExactTerm(String exactTerm, boolean active) {
		Set<Description> out = new HashSet<>();
		try (PreparedStatement ps = dataset.getConnection().prepareStatement(
				"SELECT id, effectiveTime, active, moduleId, conceptId, languageCode, typeId, term, "
				+ "caseSignificanceId, is_text_definition FROM description "
				+ "WHERE term = ? AND active = ?")) {
			ps.setString(1, exactTerm);
			ps.setString(2, active ? "1" : "0");
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String id = rs.getString(1);
					String et = rs.getString(2);
					out.add(new DuckDomain.DuckDescription(id, et, "1".equals(rs.getString(3)),
							rs.getString(4), et != null && !et.isEmpty(), rs.getString(5), rs.getString(6),
							rs.getString(7), rs.getString(8), rs.getString(9), rs.getBoolean(10),
							acceptabilityOf(id)));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("findByExactTerm failed", e);
		}
		return out;
	}

	private Map<String, String> acceptabilityOf(String descriptionId) {
		Map<String, String> out = new HashMap<>();
		try (PreparedStatement ps = dataset.getConnection().prepareStatement(
				"SELECT refsetId, acceptabilityId FROM language_refset "
				+ "WHERE referencedComponentId = ? AND active = '1'")) {
			ps.setString(1, descriptionId);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					out.put(rs.getString(1), rs.getString(2));
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("acceptabilityOf failed for " + descriptionId, e);
		}
		return out;
	}

	@Override
	public Set<Description> findMatchingDescriptionInHierarchy(Concept concept, Description description) {
		if (concept == null || Constants.ROOT_CONCEPT.equals(concept.getId())) {
			return Collections.emptySet();
		}
		String term = description.getTerm();
		if (term == null || term.trim().isEmpty()) {
			return Collections.emptySet();
		}
		String languageCode = description.getLanguageCode();
		Set<Description> matching = findActiveDescriptionByExactTerm(term).stream()
				.filter(d -> d.getLanguageCode().equals(languageCode))
				.collect(Collectors.toSet());
		if (matching.isEmpty()) {
			return Collections.emptySet();
		}
		Set<String> conceptHierarchyRootIds = conceptService.findTopLevelHierarchiesOfConcept(concept);
		if (conceptHierarchyRootIds == null) {
			return Collections.emptySet();
		}
		return matching.stream().filter(d -> {
			Set<String> statedAncestors =
					conceptService.findStatedAncestorsOfConcepts(Collections.singletonList(d.getConceptId()));
			return statedAncestors.stream().anyMatch(conceptHierarchyRootIds::contains);
		}).collect(Collectors.toSet());
	}

	@Override
	public Set<String> findParentsNotContainingSemanticTag(Concept concept, String termSemanticTag,
														   String... languageRefsetIds) {
		// Reference iterates concept.getRelationships() and keeps destinations
		// whose active FSN carries a different semantic tag. validIsaStatedRelationship
		// requires active, IS_A, NOT GCI, and characteristicType STATED.
		Set<String> conceptIds = new HashSet<>();
		for (Relationship relationship : concept.getRelationships()) {
			if (!(relationship.isActive()
					&& Constants.IS_A.equals(relationship.getTypeId())
					&& !relationship.isAxiomGCI()
					&& Constants.STATED_RELATIONSHIP.equals(relationship.getCharacteristicTypeId()))) {
				continue;
			}
			String destinationId = relationship.getDestinationId();
			Concept parent = conceptService.findById(destinationId);
			if (parent == null) {
				continue;
			}
			for (Description d : parent.getDescriptions()) {
				if (!d.isActive() || !Constants.FSN.equals(d.getTypeId())) {
					continue;
				}
				boolean matchedAcceptability = isMatchedAcceptability(languageRefsetIds, d);
				if ((languageRefsetIds == null || matchedAcceptability)
						&& !termSemanticTag.equals(DescriptionHelper.getTag(d.getTerm()))) {
					conceptIds.add(destinationId);
				}
			}
		}
		return conceptIds;
	}

	private boolean isMatchedAcceptability(String[] languageRefsetIds, Description description) {
		if (languageRefsetIds == null) {
			return false;
		}
		for (String languageRefsetId : languageRefsetIds) {
			if (PREFERRED_ACCEPTABILITY.equals(description.getAcceptabilityMap().get(languageRefsetId))) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------
	// Test-resource lookups: no repository access, delegated verbatim.
	// ------------------------------------------------------------------

	@Override
	public String getCaseSensitiveWordsErrorMessage(Description description) {
		return DescriptionHelper.getCaseSensitiveWordsErrorMessage(
				description, testResourceProvider.getCaseSignificantWords());
	}

	@Override
	public String getLanguageSpecificErrorMessage(Description description) {
		return DescriptionHelper.getLanguageSpecificErrorMessage(
				description, testResourceProvider.getUsToGbTermMap());
	}

	@Override
	public boolean isRecognisedSemanticTag(String termSemanticTag, String language) {
		return testResourceProvider.getSemanticTagsByLanguage(Collections.singleton(language))
				.contains(termSemanticTag);
	}

	@Override
	public boolean isSemanticTagCompatibleWithinHierarchy(String testTerm, Set<String> topLevelSemanticTags) {
		String tag = getTag(testTerm);
		if (tag == null) {
			return false;
		}
		Map<String, Set<String>> semanticTagMap = testResourceProvider.getSemanticHierarchyMap();
		for (String topLevelSemanticTag : topLevelSemanticTags) {
			Set<String> compatible = semanticTagMap.get(topLevelSemanticTag);
			if (compatible != null && !compatible.isEmpty() && compatible.contains(tag)) {
				return true;
			}
		}
		return false;
	}

	/** Private in the reference, reproduced: a tag containing brackets is not a tag. */
	private static String getTag(String term) {
		final Matcher matcher = DescriptionHelper.TAG_PATTERN.matcher(term);
		if (matcher.matches()) {
			String result = matcher.group(1);
			if (result != null && (result.contains("(") || result.contains(")"))) {
				return null;
			}
			return result;
		}
		return null;
	}
}
