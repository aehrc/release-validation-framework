package org.ihtsdo.rvf.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Switches off Boot's database auto-configuration when the DuckDB engine is
 * selected.
 *
 * <p>Turning off RVF's own beans is not sufficient on its own. {@code
 * spring.datasource.url} is set in {@code application.properties} and the MySQL
 * driver is on the classpath, so removing the {@code dataSource} bean does not
 * leave the application without one - it hands the job to {@link
 * DataSourceAutoConfiguration}, which builds a Hikari pool from those same
 * properties, {@link HibernateJpaAutoConfiguration} then builds an
 * EntityManagerFactory on it, and {@code spring.jpa.hibernate.ddl-auto=create}
 * runs DDL against a MySQL that is not there. The application fails to start
 * for exactly the reason it did before, with no RVF bean involved.
 *
 * <p>An {@link EnvironmentPostProcessor} rather than
 * {@code @SpringBootApplication(exclude = ...)} because the exclusion has to be
 * conditional and annotation attributes are not; and rather than a profile,
 * because profile-specific property files are loaded during config-data
 * processing, which has already finished by the time any property this could
 * read is visible.
 *
 * <p>Does nothing at all unless {@value ExecutionEngine#PROPERTY} is exactly
 * {@code duckdb}: in MySQL mode no property source is added and the environment
 * is returned untouched.
 */
public class DuckDbEngineEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

	static final String PROPERTY_SOURCE_NAME = "rvf-duckdb-engine";

	private static final String EXCLUDE_PROPERTY = "spring.autoconfigure.exclude";

	/**
	 * Referenced as classes, not as strings, so that a Boot upgrade that moves
	 * one of them is a compile error here rather than an
	 * {@code IllegalStateException} at startup naming a class nobody recognises.
	 */
	private static final List<String> EXCLUDED_AUTO_CONFIGURATION = List.of(
			DataSourceAutoConfiguration.class.getName(),
			DataSourceTransactionManagerAutoConfiguration.class.getName(),
			HibernateJpaAutoConfiguration.class.getName(),
			JpaRepositoriesAutoConfiguration.class.getName());

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		if (!ExecutionEngine.isDuckDb(environment.getProperty(ExecutionEngine.PROPERTY))) {
			return;
		}
		Set<String> excludes = new LinkedHashSet<>(EXCLUDED_AUTO_CONFIGURATION);
		// Merged rather than overwritten: a deployment that already excludes
		// something for its own reasons keeps that exclusion.
		String existing = environment.getProperty(EXCLUDE_PROPERTY);
		if (StringUtils.hasText(existing)) {
			excludes.addAll(StringUtils.commaDelimitedListToSet(existing));
		}
		environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME,
				Map.of(EXCLUDE_PROPERTY, String.join(",", excludes))));
	}

	@Override
	public int getOrder() {
		// After config data, or rvf.execution.engine set in application.properties
		// is not yet readable and only a command-line or system property would
		// ever select the engine.
		return ConfigDataEnvironmentPostProcessor.ORDER + 1;
	}
}
