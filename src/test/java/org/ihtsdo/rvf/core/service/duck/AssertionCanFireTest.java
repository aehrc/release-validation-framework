package org.ihtsdo.rvf.core.service.duck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assertions that report a pass they could never have failed.
 *
 * <p>Found on 2026-09-10 while measuring coverage, in the amtv4 corpus: twelve
 * assertions whose SQL cannot produce a finding for any release whatsoever. They
 * are not slow, not skipped and not erroring - they run, insert nothing, and are
 * reported in {@code assertionsPassed} with a failure count of zero. A green
 * result from a check that has never checked anything is worse than a red one.
 *
 * <p>Two shapes, both turning on the same SQL fact - that a {@code SELECT} which
 * reads no rows still RETURNS a row:
 *
 * <pre>
 *   NOT EXISTS(SELECT GET_CR_ADRS_PT(x) = 'y')      -- no FROM at all
 *   NOT EXISTS(SELECT COUNT(1) FROM ccsRefset_f)    -- ungrouped aggregate
 * </pre>
 *
 * <p>A {@code FROM}-less {@code SELECT} yields exactly one row. So does
 * {@code COUNT} with no {@code GROUP BY}, even over an empty table - which is the
 * sharper case, because the assertion carrying it exists to detect that the file
 * is empty. Demonstrated rather than argued: with {@code ccsRefset_f} created and
 * left empty, {@code SELECT COUNT(1) FROM ccsRefset_f} returns {@code (0)},
 * {@code EXISTS} is therefore true, {@code NOT EXISTS} is false, and the
 * assertion inserts nothing into {@code qa_result}. The release ships no
 * ccsRefset file at all, so the condition it looks for was present the whole
 * time.
 *
 * <p><b>Why this is a test and not a note.</b> The corpus is authored elsewhere
 * and arrives as SQL; nothing in the pipeline reads it for meaning. The store IS
 * the compiled form of what we execute, so it is the one place a structurally
 * dead assertion can be caught mechanically. The bundled international corpus has
 * none today and this pins that - a corpus change that introduces one fails here
 * with the assertion named, instead of shipping a check that quietly always
 * passes.
 *
 * <p>The amtv4 pack is not bundled - this repository is public and that corpus is
 * not - so its twelve cannot be pinned here. They are recorded by shape and count
 * in duck/PLAN.md 3.19 and belong upstream, with the corpus.
 */
class AssertionCanFireTest {

	/** {@code NOT EXISTS ( SELECT} ... and then we walk the parentheses. */
	private static final Pattern NOT_EXISTS_SELECT =
			Pattern.compile("NOT\\s+EXISTS\\s*\\(\\s*SELECT\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern FROM = Pattern.compile("\\bFROM\\b", Pattern.CASE_INSENSITIVE);

	private static final Pattern AGGREGATE =
			Pattern.compile("\\b(COUNT|SUM|MAX|MIN|AVG)\\s*\\(", Pattern.CASE_INSENSITIVE);

	private static final Pattern GROUPED =
			Pattern.compile("\\bGROUP\\s+BY\\b|\\bHAVING\\b", Pattern.CASE_INSENSITIVE);

	/** A comparison to NULL is NULL, never true. */
	private static final Pattern NULL_COMPARISON =
			Pattern.compile("([\\w.]+)\\s*(?:=|<>|!=)\\s*\\(?\\s*NULL\\s*\\)?", Pattern.CASE_INSENSITIVE);

	/**
	 * One of the corpus's own predicates, required and forbidden as ADJACENT
	 * conjuncts.
	 *
	 * <p>Adjacency is load-bearing. A first version scanned the whole statement
	 * for the same call negated somewhere and plain somewhere else, and flagged
	 * two assertions that demonstrably fire - the same predicate legitimately
	 * appears on both sides of an OR, and in separate EXISTS subqueries, which
	 * are separate scopes. Two false positives out of four findings is how a
	 * linter gets switched off. Separated by nothing but {@code AND} cannot span
	 * an OR or a subquery boundary, and is the shape both real defects have.
	 */
	private static final Pattern ADJACENT_CONTRADICTION = Pattern.compile(
			"(NOT\\s+)?(\\b(?:is\\w+_cr(?:_refset)?|get_cr_\\w+)\\s*\\([^()]*\\))"
					+ "\\s+AND\\s+"
					+ "(NOT\\s+)?(\\b(?:is\\w+_cr(?:_refset)?|get_cr_\\w+)\\s*\\([^()]*\\))",
			Pattern.CASE_INSENSITIVE);

	@Test
	void noBundledAssertionIsStructurallyUnableToFire() throws Exception {
		List<String> dead = new ArrayList<>();
		JsonNode assertions = store().get("assertions");
		for (Iterator<Map.Entry<String, JsonNode>> it = assertions.fields(); it.hasNext(); ) {
			Map.Entry<String, JsonNode> entry = it.next();
			String file = entry.getValue().path("file").asText(entry.getKey());
			StringBuilder sql = new StringBuilder();
			entry.getValue().path("statements").forEach(s -> sql.append(s.asText()).append(' '));
			String reason = whyItCannotFire(sql.toString());
			if (reason != null) {
				dead.add(file + " - " + reason);
			}
		}
		assertTrue(dead.isEmpty(),
				() -> dead.size() + " assertion(s) cannot report a finding for ANY release,"
						+ " so they pass unconditionally:\n  " + String.join("\n  ", dead)
						+ "\nSee duck/PLAN.md 3.19. Fix the SQL in the corpus - a check that"
						+ " cannot fail is worse than one that does.");
	}

	/**
	 * The detector has to be shown to detect, or the test above is a green light
	 * wired to nothing - the same failure it exists to catch.
	 *
	 * <p>Both statements are the real shapes, reduced to the part that matters
	 * and with the amtv4 identifiers removed, since they do not belong in this
	 * repository.
	 */
	@Test
	void theDetectorCatchesTheContradictionAndTheNullComparison() {
		// Both found in the amtv4 corpus on 2026-09-11, both verified silent in a
		// real run before being called defects.
		String contradiction = "INSERT INTO qa_result SELECT id FROM prospective.concept_active"
				+ " WHERE NOT isActiveMemberOf_cr_refset(id, 111) AND"
				+ " isActiveMemberOf_cr_refset(id, 111) AND active = 1";
		assertTrue(whyItCannotFire(contradiction) != null
						&& whyItCannotFire(contradiction).contains("adjacent"),
				"a predicate required and forbidden as adjacent conjuncts has to be reported");

		String nullCompare = "INSERT INTO qa_result SELECT id FROM prospective.values_active"
				+ " WHERE val.typeid = (null) AND val.value IS NULL";
		assertTrue(whyItCannotFire(nullCompare) != null
						&& whyItCannotFire(nullCompare).contains("NULL"),
				"a comparison to NULL has to be reported");
	}

	/**
	 * The shapes the contradiction check must NOT flag, because the first
	 * version of it flagged exactly these and both assertions fire.
	 */
	@Test
	void theContradictionCheckToleratesOrsAndSubqueries() {
		String acrossOr = "SELECT id FROM prospective.concept_active WHERE"
				+ " (NOT isActiveMemberOf_cr_refset(id, 111) OR isActiveMemberOf_cr_refset(id, 111))"
				+ " AND active = 1";
		assertTrue(whyItCannotFire(acrossOr) == null,
				"either side of an OR is satisfiable, so this is not a contradiction");

		String acrossSubquery = "SELECT id FROM prospective.concept_active a WHERE"
				+ " NOT isActiveMemberOf_cr_refset(a.id, 111) AND EXISTS(SELECT 1 FROM"
				+ " prospective.relationship_active WHERE isActiveMemberOf_cr_refset(a.id, 111))";
		assertTrue(whyItCannotFire(acrossSubquery) == null,
				"a subquery is a separate scope, not the same conjunction");
	}

	@Test
	void theDetectorCatchesBothShapes() {
		String fromLess = "INSERT INTO qa_result (details) SELECT 'x' FROM (SELECT 1 FROM dual"
				+ " WHERE NOT EXISTS(SELECT GET_CR_ADRS_PT(1) = 'a name')) AS query";
		assertTrue(whyItCannotFire(fromLess) != null && whyItCannotFire(fromLess).contains("FROM-less"),
				"a NOT EXISTS over a FROM-less SELECT has to be reported");

		String ungrouped = "INSERT INTO qa_result (details) SELECT 'x' FROM (SELECT '0' FROM dual"
				+ " WHERE NOT EXISTS(SELECT COUNT(1) FROM prospective.ccsRefset_f)) AS query";
		assertTrue(whyItCannotFire(ungrouped) != null
						&& whyItCannotFire(ungrouped).contains("ungrouped aggregate"),
				"a NOT EXISTS over COUNT with no GROUP BY has to be reported");
	}

	/**
	 * And the shapes it must leave alone, or it fails every honest assertion in
	 * the corpus. All three are patterns the bundled 360 actually use.
	 */
	@Test
	void theDetectorPassesHealthySql() {
		String correlated = "INSERT INTO qa_result SELECT a.id FROM prospective.concept_s a"
				+ " WHERE NOT EXISTS(SELECT 1 FROM prospective.description_s b"
				+ " WHERE b.conceptid = a.id)";
		assertTrue(whyItCannotFire(correlated) == null,
				"a correlated NOT EXISTS over a table is the normal way to write"
						+ " 'has no matching row' and must not be flagged");

		String outerAggregate = "INSERT INTO qa_result SELECT COUNT(*) FROM prospective.concept_s"
				+ " WHERE active = '1'";
		assertTrue(whyItCannotFire(outerAggregate) == null,
				"an aggregate in the outer projection is not inside a NOT EXISTS");

		String groupedInside = "INSERT INTO qa_result SELECT a.id FROM prospective.concept_s a"
				+ " WHERE NOT EXISTS(SELECT COUNT(1) FROM prospective.description_s b"
				+ " WHERE b.conceptid = a.id GROUP BY b.conceptid)";
		assertTrue(whyItCannotFire(groupedInside) == null,
				"an aggregate WITH a GROUP BY returns no row for an empty group,"
						+ " so that NOT EXISTS is a real test");
	}

	/**
	 * Null when the statement could report something.
	 *
	 * <p>Only {@code NOT EXISTS} is examined, and only its own subquery: a bare
	 * {@code EXISTS(SELECT 1)} is a normal way to write a constant true, and an
	 * ungrouped aggregate in the OUTER projection is how half the corpus counts
	 * things. The defect is specifically a negated existence test over something
	 * that always exists.
	 */
	private static String whyItCannotFire(String sql) {
		Matcher m = NOT_EXISTS_SELECT.matcher(sql);
		while (m.find()) {
			String inner = subqueryBody(sql, m.end());
			if (inner == null) {
				continue;
			}
			if (!FROM.matcher(inner).find()) {
				return "NOT EXISTS over a FROM-less SELECT, which always returns one row";
			}
			String projection = FROM.split(inner, 2)[0];
			if (AGGREGATE.matcher(projection).find() && !GROUPED.matcher(inner).find()) {
				return "NOT EXISTS over an ungrouped aggregate, which returns one row"
						+ " even when the table is empty";
			}
		}
		Matcher c = ADJACENT_CONTRADICTION.matcher(sql);
		while (c.find()) {
			String left = c.group(2).replaceAll("\\s+", "").toLowerCase();
			String right = c.group(4).replaceAll("\\s+", "").toLowerCase();
			boolean leftNegated = c.group(1) != null;
			boolean rightNegated = c.group(3) != null;
			if (left.equals(right) && leftNegated != rightNegated) {
				return "the same predicate is required and forbidden as adjacent"
						+ " conjuncts: " + c.group(2).trim();
			}
		}
		Matcher n = NULL_COMPARISON.matcher(sql);
		if (n.find()) {
			return n.group(1) + " is compared to NULL, which is never true -"
					+ " use IS NULL, or bind the value this was meant to test";
		}
		return null;
	}

	/** The text between {@code SELECT} and the paren that closes its subquery. */
	private static String subqueryBody(String sql, int afterSelect) {
		int depth = 1;
		for (int i = afterSelect; i < sql.length(); i++) {
			char c = sql.charAt(i);
			if (c == '(') {
				depth++;
			} else if (c == ')') {
				depth--;
				if (depth == 0) {
					return sql.substring(afterSelect, i);
				}
			}
		}
		return null;
	}

	private static JsonNode store() throws Exception {
		try (InputStream in = AssertionCanFireTest.class.getResourceAsStream("/duck/store.json")) {
			return new ObjectMapper().readTree(in);
		}
	}
}
