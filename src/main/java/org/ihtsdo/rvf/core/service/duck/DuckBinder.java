package org.ihtsdo.rvf.core.service.duck;

import org.ihtsdo.otf.RF2Constants;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Substitutes one run's values into a precompiled statement. Textual only.
 *
 * <p>The Java half of the publisher's {@code bind()}, and a deliberate mirror of
 * {@link org.ihtsdo.rvf.core.service.util.MySqlQueryTransformer}: same
 * placeholders, same values, same defaults. Where the two must agree and it is
 * not obvious, the reason is recorded at the line.
 *
 * <p>The sentinel strings come from the store, not from constants here.
 * Hardcoding "rvfph_prospective_" would be a copy of a publisher private that
 * drifts the first time it changes, and the failure mode is a query that still
 * runs and answers the wrong question.
 */
public final class DuckBinder {

	/**
	 * What a statement needs that this run cannot give it.
	 *
	 * <p>{@link #sql()} is null exactly when {@link #skippedFor()} is set.
	 */
	public record Bound(String sql, String skippedFor) {

		public boolean isSkipped() {
			return skippedFor != null;
		}
	}

	private final Map<String, String> sentinels;
	private final Config config;

	/**
	 * The run inputs, kept separate from MysqlExecutionConfig so this stays
	 * testable without a Spring context and without a database.
	 */
	public record Config(long runId, String prospectiveSchema, String previousSchema,
			String dependencySchema, String qaResultTable, String defaultModuleId,
			Collection<String> includedModules, String version) {

		/** RVF's own fallbacks, and none of them is a blank. */
		public Config withRvfDefaults() {
			return new Config(runId, prospectiveSchema, previousSchema, dependencySchema,
					qaResultTable,
					// MySqlQueryTransformer: defaultModuleId falls back to
					// SCTID_CORE_MODULE. A blank is NOT a neutral stand-in -
					// <MODULEID> lands in a BIGINT concept_id, where "" fails the
					// whole assertion with "Could not convert string '' to INT64".
					StringUtils.hasLength(defaultModuleId) ? defaultModuleId
							: RF2Constants.SCTID_CORE_MODULE,
					includedModules,
					// MySqlQueryTransformer: the version, or the literal
					// NOT_SUPPLIED. Also not blankable - every
					// `effectivetime = '<VERSION>'` comparison would then match
					// nothing, so an assertion asking "is there NO row for this
					// version" flags every row it looks at.
					StringUtils.hasLength(version) ? version : "NOT_SUPPLIED");
		}
	}

	public DuckBinder(Map<String, String> sentinels, Config config) {
		this.sentinels = sentinels;
		this.config = config.withRvfDefaults();
	}

	public Bound bind(String statement, String assertionId) {
		String s = statement;
		for (Map.Entry<String, String> e : sentinels.entrySet()) {
			s = s.replace(e.getValue(), valueFor(e.getKey(), assertionId));
		}
		// MySqlQueryTransformer drops any statement still naming a release it
		// does not have - per STATEMENT, not per assertion, so an assertion whose
		// other statements are runnable still runs them. Matched exactly here:
		// unbound means skipped, never failed. Executing anyway is not a stricter
		// check, it is 43 identical "syntax error at or near <" rows that bury
		// the real failures.
		for (String release : List.of("<PREVIOUS>", "<DEPENDENCY>")) {
			if (s.contains(release)) {
				return new Bound(null, release);
			}
		}
		return new Bound(s.replaceAll("\\bqa_result\\b", config.qaResultTable()), null);
	}

	/**
	 * The run's value for a placeholder.
	 *
	 * <p>A release this run does not hold resolves back to its own PLACEHOLDER
	 * text, not to its sentinel and not to null. That is what makes the skip
	 * check above possible and its message readable, and it is what the
	 * publisher's bind() does for the same reason. Leaving the sentinel in place
	 * instead looks identical until you try to detect it - the check searches for
	 * "&lt;PREVIOUS&gt;" and finds "rvfph_previous_", so every such statement
	 * sails through to execution and fails on a syntax error.
	 */
	private String valueFor(String placeholder, String assertionId) {
		return switch (placeholder) {
			case "<RUNID>" -> String.valueOf(config.runId());
			case "<ASSERTIONUUID>" -> assertionId;
			case "<PROSPECTIVE>", "<TEMP>" -> config.prospectiveSchema();
			case "<PREVIOUS>" -> orPlaceholder(config.previousSchema(), "<PREVIOUS>");
			case "<DEPENDENCY>" -> orPlaceholder(config.dependencySchema(), "<DEPENDENCY>");
			case "<MODULEID>" -> config.defaultModuleId();
			case "<VERSION>" -> config.version();
			// <MODULEIDS> and <INCLUDED_MODULES> are the same substitution under
			// two names; this fork predates IHTSDO's rename. <INCLUDED_MODULES>
			// additionally has a documented "no filter configured" value, because
			// the assertions using it branch on `'NULL' = '<INCLUDED_MODULES>'`.
			case "<MODULEIDS>" -> includedModules();
			case "<INCLUDED_MODULES>" -> includedModules().isEmpty() ? "NULL" : includedModules();
			default -> "";
		};
	}

	private String includedModules() {
		return config.includedModules() == null ? ""
				: String.join(",", config.includedModules());
	}

	private static String orPlaceholder(String schema, String placeholder) {
		return Optional.ofNullable(schema).filter(StringUtils::hasLength).orElse(placeholder);
	}
}
