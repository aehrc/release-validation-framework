package org.ihtsdo.rvf.config;

import jakarta.persistence.EntityManagerFactory;
import org.ihtsdo.rvf.App;
import org.ihtsdo.rvf.core.service.AssertionService;
import org.ihtsdo.rvf.core.service.MysqlValidationService;
import org.ihtsdo.rvf.core.service.ReleaseDataManager;
import org.ihtsdo.rvf.core.service.RvfDynamicDataSource;
import org.ihtsdo.rvf.core.service.ValidationRunner;
import org.ihtsdo.rvf.core.service.duck.DuckDbValidationService;
import org.ihtsdo.rvf.importer.RvfAssertionsDatabasePrimerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.repository.Repository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.web.WebAppConfiguration;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The application starts with no database in existence.
 *
 * <p>This is the deliverable, not a smoke test. RVF could not previously reach a
 * running ApplicationContext without a reachable MySQL: {@code
 * spring.jpa.hibernate.ddl-auto=create} ran DDL during refresh, {@code
 * ReleaseDataManager.init()} enumerated catalogs on the datasource from a
 * {@code @PostConstruct}, and the assertion primer imported the corpus into a
 * schema. All three are startup work, so all three failed fast.
 *
 * <p>The datasource URL below points at a port nothing listens on, deliberately.
 * A context that starts against {@code localhost:3306} on a developer machine
 * with MySQL running proves nothing; this one can only pass by never opening a
 * connection. Note there is no Testcontainer here and no {@code @Container} -
 * the absence of that machinery is part of what is being asserted.
 */
@SpringBootTest(classes = {App.class}, properties = {
		"rvf.execution.engine=duckdb",
		// Reserved, unroutable, and privileged: connecting is not merely
		// unlikely to succeed, it cannot.
		"spring.datasource.url=jdbc:mysql://192.0.2.1:1/nothing-here",
		"spring.datasource.username=nobody",
		"spring.datasource.password=nothing"})
@ActiveProfiles("test")
@WebAppConfiguration
class DuckDbEngineContextTest {

	@Autowired
	private ConfigurableApplicationContext context;

	@Test
	void theContextStartsWithNoDataSourceAtAll() {
		assertTrue(context.isRunning(), "context should be running in duckdb mode");
		// Not "the RVF dataSource bean is gone" - ANY DataSource. Removing only
		// RVF's own bean hands the job to DataSourceAutoConfiguration, which
		// builds a Hikari pool from the very same properties and fails in the
		// same place.
		assertEquals(0, context.getBeanNamesForType(DataSource.class).length,
				"no DataSource of any kind may exist in duckdb mode");
	}

	@Test
	void thereIsNoJpaAndNoRepositories() {
		// No EntityManagerFactory means Hibernate was never built, which is what
		// makes ddl-auto=create harmless rather than fatal.
		assertEquals(0, context.getBeanNamesForType(EntityManagerFactory.class).length,
				"no EntityManagerFactory - nothing may run DDL");
		assertEquals(0, context.getBeanNamesForType(Repository.class).length,
				"no Spring Data repositories - they would need an EntityManagerFactory");
		assertEquals(0, context.getBeanNamesForType(AssertionService.class).length,
				"the JPA-backed assertion service is replaced by DuckAssertionSource");
	}

	@Test
	void theStartupWorkThatNeededMysqlIsNotRegistered() {
		// Each of these was an established blocker: a @PostConstruct that
		// connected, or the bean that owns one transitively.
		assertEquals(0, context.getBeanNamesForType(ReleaseDataManager.class).length,
				"ReleaseDataManager.init() enumerates catalogs on the datasource");
		assertEquals(0, context.getBeanNamesForType(RvfAssertionsDatabasePrimerService.class).length,
				"the assertion primer imports the corpus into MySQL at startup");
		assertEquals(0, context.getBeanNamesForType(RvfDynamicDataSource.class).length);
		assertEquals(0, context.getBeanNamesForType(MysqlValidationService.class).length);
		assertEquals(0, context.getBeanNamesForType(ValidationRunner.class).length,
				"the MySQL orchestrator goes with them; the DuckDB runner is not wired yet");
	}

	@Test
	void theDuckDbValidationServiceIsThereInsteadAndCanBeBuilt() {
		// The one bean this mode ADDS. Worth asserting for two reasons: its
		// condition is the mirror image of @ConditionalOnMysqlEngine and has to
		// be spelled right in the opposite direction, and Spring builds
		// singletons eagerly - so a bean present here is also a bean whose
		// constructor ran without a datasource, an AssertionService or a
		// configured store.
		assertEquals(1, context.getBeanNamesForType(DuckDbValidationService.class).length,
				"the DuckDB validation service replaces MysqlValidationService in this mode");
	}

	@Test
	void theEngineIsNotSelectedByAccident() {
		// matchIfMissing on the condition means every bean above comes back for
		// any value that is not exactly "duckdb". Guarding the spelling here
		// because the failure mode of a typo is a production deployment that
		// starts cleanly with most of itself missing.
		assertEquals(ExecutionEngine.DUCKDB, context.getEnvironment().getProperty(ExecutionEngine.PROPERTY));
	}
}
