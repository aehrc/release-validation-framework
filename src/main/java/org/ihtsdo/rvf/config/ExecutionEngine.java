package org.ihtsdo.rvf.config;

/**
 * Which engine executes a validation, and therefore whether this process needs
 * a relational database at all.
 *
 * <p>{@code mysql} is the only value production has ever run and stays the
 * default in every code path: absent property, blank property, anything that is
 * not exactly {@code duckdb}. The DuckDB engine is opt-in because the two modes
 * differ in which beans exist, and a typo that silently dropped the MySQL half
 * of the application would be a very quiet outage.
 */
public final class ExecutionEngine {

	/** Property that selects the engine. */
	public static final String PROPERTY = "rvf.execution.engine";

	/** MySQL: the production engine, and the default when the property is absent. */
	public static final String MYSQL = "mysql";

	/**
	 * DuckDB: no datasource, no JPA, no MySQL reachable anywhere.
	 *
	 * <p>Naming the value here rather than in the engine that implements it is
	 * deliberate: this class is the property contract, and it has to be readable
	 * by the MySQL half alone. Selecting {@code duckdb} in a build that carries
	 * no DuckDB engine is a startup failure, not a silent fallback - there is
	 * simply no {@code SqlAssertionValidationService} to inject.
	 */
	public static final String DUCKDB = "duckdb";

	/**
	 * Whether a property value selects the DuckDB engine.
	 *
	 * <p>Case-insensitive, and trimmed, because {@code @ConditionalOnProperty}
	 * is: Spring's {@code OnPropertyCondition} matches {@code havingValue} with
	 * {@code equalsIgnoreCase}. Anything comparing this property with
	 * {@code equals} disagrees with the annotations for a value like
	 * {@code DuckDB}, and the two halves of the switch then disagree about which
	 * engine is running - which is worse than either answer. Every check of this
	 * property outside an annotation must go through here.
	 */
	public static boolean isDuckDb(String value) {
		return value != null && DUCKDB.equalsIgnoreCase(value.trim());
	}

	private ExecutionEngine() {
	}
}
