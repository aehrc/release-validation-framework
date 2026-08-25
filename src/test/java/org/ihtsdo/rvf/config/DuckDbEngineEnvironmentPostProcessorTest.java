package org.ihtsdo.rvf.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The post-processor's blast radius, checked without starting anything.
 *
 * <p>It runs on EVERY boot of this application, production included, before any
 * bean exists. What matters most is therefore the case where it must do nothing
 * at all - and that case is not covered by the DuckDB context test, which only
 * exercises the branch that fires.
 */
class DuckDbEngineEnvironmentPostProcessorTest {

	private final DuckDbEngineEnvironmentPostProcessor processor =
			new DuckDbEngineEnvironmentPostProcessor();

	@Test
	void mysqlModeLeavesTheEnvironmentUntouched() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty(ExecutionEngine.PROPERTY, ExecutionEngine.MYSQL);

		processor.postProcessEnvironment(environment, null);

		assertFalse(environment.getPropertySources().contains(
				DuckDbEngineEnvironmentPostProcessor.PROPERTY_SOURCE_NAME));
		assertFalse(environment.containsProperty("spring.autoconfigure.exclude"));
	}

	@Test
	void anAbsentPropertyIsMysqlMode() {
		// The production deployment sets nothing. If the default ever inverted,
		// it would start cleanly with its datasource and JPA silently gone.
		MockEnvironment environment = new MockEnvironment();

		processor.postProcessEnvironment(environment, null);

		assertFalse(environment.containsProperty("spring.autoconfigure.exclude"));
	}

	@Test
	void duckdbModeExcludesBootsDatabaseAutoConfiguration() {
		MockEnvironment environment = new MockEnvironment()
				.withProperty(ExecutionEngine.PROPERTY, ExecutionEngine.DUCKDB);

		processor.postProcessEnvironment(environment, null);

		String excluded = environment.getProperty("spring.autoconfigure.exclude");
		assertTrue(excluded.contains("DataSourceAutoConfiguration"), excluded);
		assertTrue(excluded.contains("HibernateJpaAutoConfiguration"), excluded);
	}

	@Test
	void anExclusionTheDeploymentAlreadyMadeSurvives() {
		// Overwriting would silently re-enable whatever a deployment had turned
		// off for its own reasons, and only in DuckDB mode - the hardest kind of
		// difference to attribute later.
		MockEnvironment environment = new MockEnvironment()
				.withProperty(ExecutionEngine.PROPERTY, ExecutionEngine.DUCKDB)
				.withProperty("spring.autoconfigure.exclude", "com.example.SomethingAutoConfiguration");

		processor.postProcessEnvironment(environment, null);

		String excluded = environment.getProperty("spring.autoconfigure.exclude");
		assertTrue(excluded.contains("com.example.SomethingAutoConfiguration"), excluded);
		assertTrue(excluded.contains("DataSourceAutoConfiguration"), excluded);
	}
}
