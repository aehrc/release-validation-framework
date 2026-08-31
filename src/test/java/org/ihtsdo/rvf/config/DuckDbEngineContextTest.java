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
import org.ihtsdo.rvf.rest.controller.TestUploadFileController;
import org.ihtsdo.rvf.rest.controller.AutomatedTestController;
import org.ihtsdo.rvf.rest.controller.AssertionController;
import org.ihtsdo.rvf.rest.controller.AssertionGroupController;
import org.ihtsdo.rvf.rest.controller.AssertionAdministrationController;
import org.ihtsdo.rvf.rest.controller.AssertionGroupAdministrationController;
import org.ihtsdo.rvf.rest.controller.ReleaseController;
import org.ihtsdo.rvf.core.service.duck.DuckAssertionService;
import org.ihtsdo.rvf.core.service.AssertionServiceImpl;
import org.ihtsdo.rvf.core.messaging.ValidationMessageListener;
import org.ihtsdo.rvf.core.service.SqlAssertionValidationService;
import org.ihtsdo.rvf.core.service.ReleaseAcquisitionService;
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
		// AssertionService itself IS present - it has to be, or every controller
		// that injects it disappears and the application cannot be asked to
		// validate anything. What must be absent is the JPA implementation.
		assertEquals(0, context.getBeanNamesForType(AssertionServiceImpl.class).length,
				"the JPA-backed assertion service would need an EntityManagerFactory");
		assertEquals(1, context.getBeanNamesForType(AssertionService.class).length,
				"exactly one implementation, so injection is unambiguous");
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
		// ValidationRunner is NOT in this list any more, and that is the point of
		// the wiring: it orchestrates five passes, four of which never touched a
		// datasource, and only the SQL pass was engine-specific. It now takes
		// SqlAssertionValidationService, so it exists in both modes - which is
		// what gives the validation queue a consumer here.
	}

	/**
	 * The submission path exists in DuckDB mode.
	 *
	 * <p>It did not, and the reason was a single injected dependency: every
	 * controller that accepts a validation takes {@code AssertionService}, whose
	 * only implementation was JPA-backed and therefore MySQL-conditional. So the
	 * application booted, served /version and /result, and had no endpoint that
	 * could be asked to validate anything - a server that looks healthy and
	 * cannot do its job.
	 *
	 * <p>The assertion CRUD controllers stay MySQL-only on purpose. They
	 * administer an assertion database, and in this mode the corpus is a
	 * published store; an endpoint that pretended to accept a write would be
	 * worse than one that is absent.
	 */
	/**
	 * The queue has a consumer, and the orchestrator behind it is engine-neutral.
	 *
	 * <p>Asserting the LISTENER matters more than asserting the runner: a run is
	 * accepted over REST, enqueued, and picked up by @JmsListener. With the
	 * listener absent the submission still succeeded and returned a run id, and
	 * the work simply never happened - the failure mode that looks exactly like
	 * a slow queue.
	 */
	@Test
	void theQueueHasAConsumerAndTheSqlPassResolvesToTheDuckDbService() {
		assertEquals(1, context.getBeanNamesForType(ValidationMessageListener.class).length,
				"without this nothing drains the validation queue");
		assertEquals(1, context.getBeanNamesForType(ValidationRunner.class).length,
				"four of its five passes never needed MySQL");
		assertEquals(1, context.getBeanNamesForType(SqlAssertionValidationService.class).length,
				"exactly one implementation, so ValidationRunner's injection is unambiguous");
		assertEquals(DuckDbValidationService.class,
				context.getBean(SqlAssertionValidationService.class).getClass());
		assertEquals(1, context.getBeanNamesForType(ReleaseAcquisitionService.class).length,
				"acquisition carries no engine condition - it downloads and unzips");
	}

	@Test
	void theSubmissionPathAndCatalogueReadsAreRegisteredAndTheCrudControllersAreNot() {
		assertEquals(1, context.getBeanNamesForType(TestUploadFileController.class).length,
				"nothing could submit a validation without this");
		assertEquals(1, context.getBeanNamesForType(AutomatedTestController.class).length);
		assertEquals(1, context.getBeanNamesForType(AssertionService.class).length,
				"exactly one AssertionService - the store-backed one");
		assertEquals(DuckAssertionService.class,
				context.getBean(AssertionService.class).getClass());

		// The catalogue is READABLE in this mode: validation-framework-browser-ui
		// calls GET /assertions and GET /groups, and both resolve through
		// AssertionService, which DuckAssertionService implements over the store.
		assertEquals(1, context.getBeanNamesForType(AssertionController.class).length,
				"the assertion browser reads the catalogue and needs no database");
		assertEquals(1, context.getBeanNamesForType(AssertionGroupController.class).length,
				"group listing goes through AssertionService, not the JPA repository");

		// Writing to it, and executing against loaded MySQL release data, do not
		// exist here. Withdrawing the beans answers 404 rather than a 500 from
		// DuckAssertionService's read-only guard.
		assertEquals(0, context.getBeanNamesForType(AssertionAdministrationController.class).length,
				"assertion CRUD administers a database this mode does not have");
		assertEquals(0, context.getBeanNamesForType(AssertionGroupAdministrationController.class).length,
				"group membership is addressed by a numeric id the store has no equivalent for");
		assertEquals(0, context.getBeanNamesForType(ReleaseController.class).length);
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
