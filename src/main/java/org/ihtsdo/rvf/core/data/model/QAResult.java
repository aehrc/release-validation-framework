package org.ihtsdo.rvf.core.data.model;

import javax.persistence.*;
import java.io.Serial;
import java.io.Serializable;


@Entity
@Table(name = "qa_result")
public class QAResult implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;
	
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id")
	private Long id;
	
	@Column(name = "run_id")
	private Long runId;

	@JoinColumn(name ="assertion_id")
	@ManyToOne
	private Assertion assertion;
	
	@Column(name = "concept_id")
	private Long conceptId;
	
	@Lob
	@Column(name ="details")
	private String details;

	@Column(name = "component_id")
	private String componentId;

	@Column(name ="table_name")
	private String tableName;

	/**
	 * Set by an assertion that has already decided which modules its failures
	 * concern, exempting those rows from the extension-module filter applied in
	 * MysqlFailuresExtractor. Nullable: assertions that do not name this column
	 * in their INSERT leave it NULL, which reads back as false and so preserves
	 * the pre-existing filtering.
	 */
	@Column(name ="skip_module_check")
	private Boolean skipModuleCheck = false;

	public String getDetails() {
		return details;
	}

	public void setDetails(final String details) {
		this.details = details;
	}

	public Long getRunId() {
		return runId;
	}

	public void setRunId(final Long runId) {
		this.runId = runId;
	}

	public Assertion getAssertion() {
		return assertion;
	}

	public void setAssertion(final Assertion assertion) {
		this.assertion = assertion;
	}

	public Long getConceptId() {
		return conceptId;
	}

	public void setConceptId(Long conceptId) {
		this.conceptId = conceptId;
	}

	public String getComponentId() {
		return componentId;
	}

	public void setComponentId(String componentId) {
		this.componentId = componentId;
	}

	public String getTableName() {
		return tableName;
	}

	public void setTableName(String tableName) {
		this.tableName = tableName;
	}

	public Boolean getSkipModuleCheck() {
		return skipModuleCheck;
	}

	public void setSkipModuleCheck(Boolean skipModuleCheck) {
		this.skipModuleCheck = skipModuleCheck;
	}
}
