package org.ihtsdo.rvf.core.service.duck;

import org.ihtsdo.otf.RF2Constants;
import org.springframework.util.StringUtils;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
			Collection<String> includedModules, String version,
			/**
			 * A schema holding the whole RF2 table set with no rows, or null.
			 *
			 * <p>Only ever used for a statement that cannot tell the difference -
			 * see {@link #bind}. Supplied by the caller because creating it is the
			 * caller's job: {@code DuckDbValidationService} materialises an empty
			 * directory, which gives every declared table a zero-row placeholder.
			 */
			String emptySchema) {

		/** The pre-empty-schema shape, for callers with nothing to offer. */
		public Config(long runId, String prospectiveSchema, String previousSchema,
				String dependencySchema, String qaResultTable, String defaultModuleId,
				Collection<String> includedModules, String version) {
			this(runId, prospectiveSchema, previousSchema, dependencySchema, qaResultTable,
					defaultModuleId, includedModules, version, null);
		}

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
					StringUtils.hasLength(version) ? version : "NOT_SUPPLIED",
					emptySchema);
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
		// A statement whose ONLY use of the absent release is an anti-join that
		// requires NULL is fully answerable without it: the join adds no rows and
		// the NULL test passes for every row, so an empty release and no release
		// give the same answer. Skipping it does not decline to answer, it
		// answers a DIFFERENT, smaller question and reports the count as though
		// it were the whole one.
		//
		// Measured on the bundled corpus, for a release with no dependency -
		// which is every edition run, and `releaseAsAnEdition=true` is what the
		// nightly submits: 16 statements across the
		// release-type-snapshot-*-successive-states assertions are anti-join
		// filters, and 28 in file-centric-snapshot-inactivated-component-module
		// use the dependency as the thing being compared against. The first
		// group loses nothing by running; the second cannot be answered at all
		// and must still be skipped, which is why this is not simply "bind an
		// empty schema and run everything" - that is MySQL's behaviour, and it
		// is how one AU assertion reported 1,405,850 findings against a
		// dependency that was not there.
		for (String release : List.of("<PREVIOUS>", "<DEPENDENCY>")) {
			if (!s.contains(release)) {
				continue;
			}
			String emptySchema = emptySchemaFor(release);
			if (emptySchema != null && onlyAntiJoined(s, release)) {
				s = s.replace(release, emptySchema);
				continue;
			}
			return new Bound(null, release);
		}
		s = padSpaceTerms(s);
		return new Bound(QA_RESULT.matcher(s).replaceAll(config.qaResultTable()), null);
	}

	/**
	 * A {@code term} reference, for reproducing MySQL's PAD SPACE comparison.
	 *
	 * <p>MySQL's collation ignores TRAILING SPACES when it compares and when it
	 * groups; DuckDB compares exactly. On RVF's own regression fixture concept
	 * 703860006 has two active descriptions whose terms differ only in trailing
	 * whitespace - {@code 'Exposure to vibration  '} and
	 * {@code 'Exposure to vibration '} - so MySQL groups them and reports the
	 * duplicate while DuckDB reported nothing. For an assertion that says "all
	 * active description terms are unique", MySQL is right and that was a false
	 * negative on our side.
	 */
	private static final Pattern TERM_REFERENCE = Pattern.compile("\\b(\\w+)\\.term\\b");

	/** GROUP BY over term: the whole statement is rewritten, keys and all. */
	private static final Pattern GROUPS_BY_TERM = Pattern.compile(
			"GROUP\\s+BY[^;]*\\bterm\\b", Pattern.CASE_INSENSITIVE);

	/**
	 * A derived table, which this rewrite must not reach into.
	 *
	 * <p>Three assertions - unique-FSN, unique-fsn-case-insensitive-checking and
	 * unique-preferred-terms - group by {@code a.term} INSIDE a derived table and
	 * then join the outer query on {@code dup.term}. Rewriting the inner
	 * projection renames that column, the outer reference stops resolving, and
	 * the assertion fails to execute at all. Trading one false negative for three
	 * assertions that do not run is a worse report.
	 *
	 * <p>Doing this correctly means keeping the projection's name -
	 * {@code rtrim(a.term) AS term} - which needs the parse tree, not a pattern.
	 * That belongs in the publisher that transpiles the corpus, which is not in
	 * this repository.
	 */
	private static final Pattern DERIVED_TABLE = Pattern.compile("\\)\\s+AS\\s+\\w+", Pattern.CASE_INSENSITIVE);

	/**
	 * Makes a statement's {@code term} comparisons trailing-space-insensitive,
	 * as MySQL's are.
	 *
	 * <p>Applied to the WHOLE statement rather than just the comparison, because
	 * a GROUP BY key and the SELECT expression over it have to agree: rewriting
	 * only the GROUP BY leaves DuckDB rejecting the statement outright.
	 *
	 * <p>Only statements that group or join on term are touched - 29 of them,
	 * measured - and the rewrite is a NO-OP on data without trailing spaces, so
	 * it cannot change the 20-odd that agree today. Nothing in the corpus
	 * inspects trailing whitespace on a term: the only two statements using trim
	 * functions work on {@code maprule} and already trim it explicitly, so this
	 * cannot silence a check that exists to find padding.
	 *
	 * <p>The proper home for this is the publisher that transpiles the corpus,
	 * which is not in this repository. Until it lands there this keeps the two
	 * engines answering the same question.
	 */
	private static String padSpaceTerms(String sql) {
		if (sql.toLowerCase().contains("rtrim(")) {
			return sql;
		}
		if (GROUPS_BY_TERM.matcher(sql).find() && !DERIVED_TABLE.matcher(sql).find()) {
			// Every reference, because a GROUP BY key and the SELECT expression
			// over it have to agree or DuckDB rejects the statement outright.
			return TERM_REFERENCE.matcher(sql).replaceAll("rtrim($1.term)");
		}
		// Everything else keeps MySQL's collation unreplicated: 21 statements in
		// 19 assertions join on term, and none of them diverges on any release
		// measured so far. The exposure is recorded rather than guessed at.
		return sql;
	}

	/**
	 * The empty stand-in for a release this run does not hold, or null.
	 *
	 * <p>Null for {@code <PREVIOUS>} deliberately. An absent previous release is
	 * not the same fact: "this component did not exist before" and "there is no
	 * before" are different answers, and a first-time release is a real case the
	 * report already handles by saying not-run. The dependency is different -
	 * a run with no dependency IS complete content, which is what
	 * {@code isExtensionValidation()} means when it reads the dependency list.
	 */
	private String emptySchemaFor(String release) {
		return "<DEPENDENCY>".equals(release) ? config.emptySchema() : null;
	}

	/**
	 * True when every mention of {@code release} is a LEFT JOIN whose alias is
	 * then required to be NULL - an anti-join, and a no-op against an empty
	 * relation.
	 *
	 * <p>Deliberately narrow: it recognises ONE shape and refuses everything
	 * else, including a shape it half-recognises. The corpus is transpiled, so
	 * the shape is uniform, and being wrong in the permissive direction here
	 * means running a check against nothing and reporting the result as though
	 * it meant something.
	 */
	private static boolean onlyAntiJoined(String sql, String release) {
		Matcher joins = Pattern.compile("LEFT\\s+JOIN\\s+" + Pattern.quote(release)
				+ "\\.\\w+\\s+AS\\s+(\\w+)", Pattern.CASE_INSENSITIVE).matcher(sql);
		int antiJoined = 0;
		while (joins.find()) {
			String alias = joins.group(1);
			if (!Pattern.compile("\\b" + Pattern.quote(alias) + "\\.\\w+\\s+IS\\s+NULL",
					Pattern.CASE_INSENSITIVE).matcher(sql).find()) {
				return false;
			}
			antiJoined++;
		}
		if (antiJoined == 0) {
			return false;
		}
		// Every occurrence has to be one of the joins just counted, or there is
		// a use this has not looked at.
		int mentions = 0;
		for (int i = sql.indexOf(release); i >= 0; i = sql.indexOf(release, i + 1)) {
			mentions++;
		}
		return mentions == antiJoined;
	}

	/**
	 * The qa_result table as a statement names it, before binding.
	 *
	 * <p>Exposed because a caller needs the SAME definition to answer "could
	 * this statement have reported a finding at all". The store declares a
	 * {@code qaResultToken} and this does not read it: the binder has always
	 * matched the literal word, so the field describes the format rather than
	 * driving the rewrite - and a caller that trusted the field got an EMPTY
	 * string from a store that omits it, making {@code contains("")} true for
	 * every statement. A word-boundary pattern cannot fail that way.
	 */
	public static final java.util.regex.Pattern QA_RESULT =
			java.util.regex.Pattern.compile("\\bqa_result\\b");

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
