package org.ihtsdo.rvf.core.service.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code getIncludedModules()} must never return null.
 *
 * <p>Not a style preference. {@code MySqlQueryTransformer} does
 * {@code config.getIncludedModules().stream()} with no guard on every statement
 * it transforms, and the two single-execution endpoints -
 * {@code POST /assertions/{id}/run} and {@code POST /groups/{id}/run} - build
 * the config with a constructor and never call the setter. So every assertion
 * run through those endpoints came back with failureCount -1 and
 * "NullPointerException ... getIncludedModules() is null", in MySQL mode, which
 * is the only mode those endpoints exist in.
 *
 * <p>Empty is also what every reader already assumes: each one tests
 * {@code CollectionUtils.isEmpty} before using the value, so null and empty were
 * always meant to behave the same.
 */
class MysqlExecutionConfigTest {

	@Test
	void aConfigBuiltByConstructorHasModulesNotNull() {
		MysqlExecutionConfig config = new MysqlExecutionConfig(1788232124359L);

		assertNotNull(config.getIncludedModules(),
				"MySqlQueryTransformer streams this without a guard");
		assertTrue(config.getIncludedModules().isEmpty());
	}

	@Test
	void theTwoArgConstructorIsTheSame() {
		MysqlExecutionConfig config = new MysqlExecutionConfig(1L, true);

		assertNotNull(config.getIncludedModules());
		assertTrue(config.getIncludedModules().isEmpty());
	}

	@Test
	void settingNullDoesNotReintroduceIt() {
		MysqlExecutionConfig config = new MysqlExecutionConfig(1L);

		config.setIncludedModules(null);

		assertNotNull(config.getIncludedModules(),
				"callers pass through whatever the request carried");
		assertTrue(config.getIncludedModules().isEmpty());
	}

	@Test
	void realModulesSurviveUntouched() {
		MysqlExecutionConfig config = new MysqlExecutionConfig(1L);

		config.setIncludedModules(List.of("32506021000036107", "900000000000207008"));

		assertEquals(List.of("32506021000036107", "900000000000207008"),
				config.getIncludedModules());
	}
}
