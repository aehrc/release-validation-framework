package org.ihtsdo.rvf.core.service.drools.duck;

import org.ihtsdo.drools.domain.Annotation;
import org.ihtsdo.drools.domain.Concept;
import org.ihtsdo.drools.domain.Description;
import org.ihtsdo.drools.domain.OntologyAxiom;
import org.ihtsdo.drools.domain.Relationship;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Adapters implementing the Drools domain interfaces over DuckDB rows.
 *
 * <p>All five interfaces are plain data, so these are records with a supplier
 * for the collection-valued members. The rules reach {@code getDescriptions()},
 * {@code getRelationships()} and friends only on the concepts they are actually
 * reasoning about, so those are resolved on demand rather than eagerly - which
 * is the whole point of not holding the release in heap.
 */
/*
 * PUBLIC, and so is every nested component class, deliberately.
 *
 * Drools evaluates rule constraints like `Concept(active == true)` with MVEL,
 * which resolves `active` reflectively against the RUNTIME class from generated
 * code in another package. A package-private implementation of a public
 * interface fails that lookup for every constraint that touches it - reported
 * not as a crash but as "An error occurred while running concept validation",
 * counted as a violation. 515 of 515 findings on the authored scope were this,
 * which reads as a backend that runs and finds things.
 */
public final class DuckDomain {

	private DuckDomain() {
	}

	public abstract static class AbstractComponent {
		final String id;
		final String effectiveTime;
		final boolean active;
		final String moduleId;
		final boolean released;

		AbstractComponent(String id, String effectiveTime, boolean active, String moduleId, boolean released) {
			this.id = id;
			this.effectiveTime = effectiveTime;
			this.active = active;
			this.moduleId = moduleId;
			this.released = released;
		}

		public String getId() {
			return id;
		}

		public boolean isActive() {
			return active;
		}

		public String getModuleId() {
			return moduleId;
		}

		public String getEffectiveTime() {
			return effectiveTime;
		}

		// RVF validates a release that has not been published yet. The incumbent
		// derives both of these from whether the component carried an
		// effectiveTime in the input, so the same rule applies here.
		public boolean isPublished() {
			return released;
		}

		public boolean isReleased() {
			return released;
		}
	}

	public static final class DuckConcept extends AbstractComponent implements Concept {
		private final String definitionStatusId;
		private final DuckConceptService service;

		DuckConcept(String id, String effectiveTime, boolean active, String moduleId, boolean released,
					String definitionStatusId, DuckConceptService service) {
			super(id, effectiveTime, active, moduleId, released);
			this.definitionStatusId = definitionStatusId;
			this.service = service;
		}

		@Override
		public String getDefinitionStatusId() {
			return definitionStatusId;
		}

		@Override
		public Collection<? extends Description> getDescriptions() {
			return service.descriptionsOf(id);
		}

		@Override
		public Collection<? extends Relationship> getRelationships() {
			return service.relationshipsOf(id);
		}

		@Override
		public Collection<? extends OntologyAxiom> getOntologyAxioms() {
			return service.axiomsOf(id);
		}

		/**
		 * Component annotations, read from the Component Annotation String Value
		 * refset. New abstract method on {@code Concept} in snomed-drools 6.0.0 -
		 * this is the {@code org.ihtsdo.drools.domain.Annotation} type whose
		 * absence from 5.7.0 is what stopped the current rules compiling there.
		 */
		@Override
		public Collection<? extends Annotation> getAnnotations() {
			return service.annotationsOf(id);
		}

		@Override
		public Map<String, Set<String>> getAssociationTargets() {
			return service.associationTargetsOf(id);
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Concept && id.equals(((Concept) o).getId());
		}

		@Override
		public int hashCode() {
			return id.hashCode();
		}
	}

	public static final class DuckDescription extends AbstractComponent implements Description {
		private final String conceptId;
		private final String languageCode;
		private final String typeId;
		private final String term;
		private final String caseSignificanceId;
		private final boolean textDefinition;
		private final Map<String, String> acceptabilityMap;

		DuckDescription(String id, String effectiveTime, boolean active, String moduleId, boolean released,
						String conceptId, String languageCode, String typeId, String term,
						String caseSignificanceId, boolean textDefinition, Map<String, String> acceptabilityMap) {
			super(id, effectiveTime, active, moduleId, released);
			this.conceptId = conceptId;
			this.languageCode = languageCode;
			this.typeId = typeId;
			this.term = term;
			this.caseSignificanceId = caseSignificanceId;
			this.textDefinition = textDefinition;
			this.acceptabilityMap = acceptabilityMap == null ? Collections.emptyMap() : acceptabilityMap;
		}

		@Override
		public String getConceptId() {
			return conceptId;
		}

		@Override
		public String getLanguageCode() {
			return languageCode;
		}

		@Override
		public String getTypeId() {
			return typeId;
		}

		@Override
		public String getTerm() {
			return term;
		}

		@Override
		public String getCaseSignificanceId() {
			return caseSignificanceId;
		}

		@Override
		public boolean isTextDefinition() {
			return textDefinition;
		}

		@Override
		public Map<String, String> getAcceptabilityMap() {
			return acceptabilityMap;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Description && id.equals(((Description) o).getId());
		}

		@Override
		public int hashCode() {
			return id.hashCode();
		}
	}

	public static final class DuckRelationship extends AbstractComponent implements Relationship {
		private final String axiomId;
		private final boolean axiomGCI;
		private final String sourceId;
		private final String destinationId;
		private final String typeId;
		private final String characteristicTypeId;
		private final String concreteValue;
		private final int relationshipGroup;

		DuckRelationship(String id, String effectiveTime, boolean active, String moduleId, boolean released,
						 String axiomId, boolean axiomGCI, String sourceId, String destinationId, String typeId,
						 String characteristicTypeId, String concreteValue, int relationshipGroup) {
			super(id, effectiveTime, active, moduleId, released);
			this.axiomId = axiomId;
			this.axiomGCI = axiomGCI;
			this.sourceId = sourceId;
			this.destinationId = destinationId;
			this.typeId = typeId;
			this.characteristicTypeId = characteristicTypeId;
			this.concreteValue = concreteValue;
			this.relationshipGroup = relationshipGroup;
		}

		@Override
		public String getAxiomId() {
			return axiomId;
		}

		@Override
		public boolean isAxiomGCI() {
			return axiomGCI;
		}

		@Override
		public String getSourceId() {
			return sourceId;
		}

		@Override
		public int getRelationshipGroup() {
			return relationshipGroup;
		}

		@Override
		public String getTypeId() {
			return typeId;
		}

		@Override
		public String getDestinationId() {
			return destinationId;
		}

		@Override
		public String getCharacteristicTypeId() {
			return characteristicTypeId;
		}

		@Override
		public String getConcreteValue() {
			return concreteValue;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Relationship && id != null && id.equals(((Relationship) o).getId());
		}

		@Override
		public int hashCode() {
			return id == null ? 0 : id.hashCode();
		}
	}

	public static final class DuckOntologyAxiom extends AbstractComponent implements OntologyAxiom {
		private final String referencedComponentId;
		private final String owlExpression;
		private final Collection<String> namedConcepts;
		private final boolean primitive;
		private final boolean axiomGCI;

		DuckOntologyAxiom(String id, String effectiveTime, boolean active, String moduleId, boolean released,
						  String referencedComponentId, String owlExpression, Collection<String> namedConcepts,
						  boolean primitive, boolean axiomGCI) {
			super(id, effectiveTime, active, moduleId, released);
			this.referencedComponentId = referencedComponentId;
			this.owlExpression = owlExpression;
			this.namedConcepts = namedConcepts == null ? Collections.emptySet() : namedConcepts;
			this.primitive = primitive;
			this.axiomGCI = axiomGCI;
		}

		@Override
		public boolean isPrimitive() {
			return primitive;
		}

		@Override
		public String getReferencedComponentId() {
			return referencedComponentId;
		}

		@Override
		public String getOwlExpression() {
			return owlExpression;
		}

		@Override
		public Collection<String> getOwlExpressionNamedConcepts() {
			return namedConcepts;
		}

		@Override
		public boolean isAxiomGCI() {
			return axiomGCI;
		}
	}

	/**
	 * A row of the Component Annotation String Value refset. The refset id itself
	 * is the annotation's typeId, matching how snomed-boot's factory reports it.
	 */
	public static final class DuckAnnotation extends AbstractComponent implements Annotation {
		private final String conceptId;
		private final String languageDialectCode;
		private final String typeId;
		private final String value;

		DuckAnnotation(String id, String effectiveTime, boolean active, String moduleId, boolean released,
					   String conceptId, String languageDialectCode, String typeId, String value) {
			super(id, effectiveTime, active, moduleId, released);
			this.conceptId = conceptId;
			this.languageDialectCode = languageDialectCode;
			this.typeId = typeId;
			this.value = value;
		}

		@Override
		public String getConceptId() {
			return conceptId;
		}

		@Override
		public String getLanguageDialectCode() {
			return languageDialectCode;
		}

		@Override
		public String getTypeId() {
			return typeId;
		}

		@Override
		public String getValue() {
			return value;
		}
	}
}
