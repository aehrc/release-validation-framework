package org.ihtsdo.rvf.configuration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.ihtsdo.rvf.App;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.PropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.mysql.MySQLContainer;

import javax.sql.DataSource;

import java.sql.Connection;

@SpringBootTest(classes = {App.class})
@PropertySource("classpath:/application-test.properties")
@ActiveProfiles("test")
@WebAppConfiguration
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
public abstract class IntegrationTest {
	private static final Logger LOGGER = LoggerFactory.getLogger(IntegrationTest.class);
	protected static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	/**
	 * An already-running MySQL to use instead of starting a container, or null.
	 *
	 * <p>Every MySQL-backed test in this repository used to be unrunnable without
	 * a Docker daemon: 14 classes reported
	 * {@code Could not find a valid Docker environment} and were counted as
	 * errors, which is indistinguishable in a summary from a real failure. The
	 * A/B stack has never needed Docker - it runs the official generic Linux
	 * tarball unprivileged, which is what makes it work on a build agent with no
	 * daemon and no sudo - and there is no reason these tests should be stricter
	 * than the thing they are testing.
	 *
	 * <p>Set {@code -Drvf.test.mysql.url=...} (or {@code RVF_TEST_MYSQL_URL}) and
	 * this uses it; leave it unset and Testcontainers behaves exactly as before.
	 * The server must allow {@code LOCAL INFILE}, because that is how RVF loads
	 * a release.
	 */
	private static final String EXTERNAL_URL = property("rvf.test.mysql.url", "RVF_TEST_MYSQL_URL");
	private static final String EXTERNAL_USER = orDefault(property("rvf.test.mysql.username", "RVF_TEST_MYSQL_USERNAME"), "root");
	private static final String EXTERNAL_PASSWORD = orDefault(property("rvf.test.mysql.password", "RVF_TEST_MYSQL_PASSWORD"), "root");

	/** Null when an external server was named: nothing is started in that case. */
	private static final MySQLContainer CONTAINER;

	static {
		if (EXTERNAL_URL == null) {
			// Started here rather than by @Testcontainers, because the annotation
			// would start it whether or not it is wanted.
			MySQLContainer c = new TestMySQLContainer();
			c.start();
			CONTAINER = c;
		} else {
			CONTAINER = null;
			LOGGER.info("Using the MySQL at {} rather than a container", EXTERNAL_URL);
			applyInitScript();
		}
	}

	private static String property(String systemProperty, String environmentVariable) {
		String v = System.getProperty(systemProperty);
		if (v == null || v.isBlank()) {
			v = System.getenv(environmentVariable);
		}
		return v == null || v.isBlank() ? null : v;
	}

	private static String orDefault(String value, String fallback) {
		return value == null ? fallback : value;
	}

	/**
	 * What {@code withInitScript("init.sql")} does for the container: the
	 * {@code admin} user some tests authenticate as. Idempotent, and a failure
	 * here is fatal - a missing grant surfaces later as an authentication error
	 * in an unrelated test.
	 */
	private static void applyInitScript() {
		try (Connection con = java.sql.DriverManager.getConnection(EXTERNAL_URL, EXTERNAL_USER, EXTERNAL_PASSWORD);
				java.sql.Statement st = con.createStatement()) {
			st.execute("CREATE USER IF NOT EXISTS 'admin'@'%' IDENTIFIED BY 'admin'");
			st.execute("GRANT ALL PRIVILEGES ON *.* TO 'admin'@'%' WITH GRANT OPTION");
			st.execute("FLUSH PRIVILEGES");
		} catch (Exception e) {
			throw new IllegalStateException("Could not prepare " + EXTERNAL_URL
					+ ". It must be reachable, allow LOCAL INFILE, and hold the "
					+ "database named in the URL.", e);
		}
	}

	@Autowired
	private DataSource dataSource;

	@DynamicPropertySource
	static void mysqlProps(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", IntegrationTest::jdbcUrl);
		registry.add("spring.datasource.username",
				() -> CONTAINER == null ? EXTERNAL_USER : CONTAINER.getUsername());
		registry.add("spring.datasource.password",
				() -> CONTAINER == null ? EXTERNAL_PASSWORD : CONTAINER.getPassword());
	}

	private static String jdbcUrl() {
		return CONTAINER == null ? EXTERNAL_URL : CONTAINER.getJdbcUrl();
	}

	protected String getSpringBootUrl() {
		try (Connection conn = dataSource.getConnection()) {
			return conn.getMetaData().getURL();
		} catch (Exception e) {
			LOGGER.error("Error getting url", e);
			return null;
		}
	}

	/** The URL of whichever MySQL is backing this run. */
	protected String getTestcontainersUrl() {
		return jdbcUrl();
	}

	protected String getResponseBody(ResultActions resultActions) {
		try {
			return resultActions.andReturn().getResponse().getContentAsString();
		} catch (Exception e) {
			return null;
		}
	}

	protected <T> T getResponseBody(ResultActions resultActions, Class<T> clazz) {
		try {
			String responseBody = resultActions.andReturn().getResponse().getContentAsString();
			return OBJECT_MAPPER.readValue(responseBody, clazz);
		} catch (Exception e) {
			return null;
		}
	}

	protected <T> T getResponseBody(ResultActions resultActions, TypeReference<T> typeReference) {
		try {
			String responseBody = resultActions.andReturn()
					.getResponse()
					.getContentAsString();
			return OBJECT_MAPPER.readValue(responseBody, typeReference);
		} catch (Exception e) {
			return null;
		}
	}
}
