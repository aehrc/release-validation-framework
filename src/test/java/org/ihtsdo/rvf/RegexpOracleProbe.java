package org.ihtsdo.rvf;

import java.io.IOException;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Evaluates each MySQL {@code REGEXP} and the DuckDB {@code regexp_matches} it
 * was transpiled into against the SAME rows, on both engines, and reports
 * whether they select the same ids.
 *
 * <p>WHY THE ASSERTION-LEVEL A/B CANNOT ANSWER THIS: run against the AU edition,
 * all 46 regex-bearing assertions agree - and 44 agree by matching nothing at
 * all, so no pattern was ever evaluated against a matching row. That is a
 * question neither engine was given content to answer, not a pass.
 *
 * <p>WHERE A DEFECT WOULD HIDE: RVF's tables are {@code charset=utf8}, whose
 * collation is case-INSENSITIVE, so {@code term REGEXP 'ACL'} matches "acl" on
 * MySQL. DuckDB's {@code regexp_matches} is case-SENSITIVE unless passed 'i',
 * and 70 of the store's 90 calls carry no flags argument. A pattern that
 * silently stops matching does not error: the assertion reports nothing, which
 * reads as a pass.
 *
 * <p>The rows are exported from MySQL and read by DuckDB, so "the engines saw
 * different data" is not available as an explanation of a difference.
 *
 * <p>Pairing is done by {@code ci/regexp_oracle.py}, which knows the corpus
 * layout; this probe is given the pairs and owns only the measurement.
 */
public final class RegexpOracleProbe {

	/** Ids beyond this per pattern are digested but not listed. */
	private static final int SAMPLE = 3;

	private RegexpOracleProbe() {
	}

	public static void main(String[] args) throws Exception {
		if (args.length < 5) {
			System.err.println("usage: RegexpOracleProbe <pairs.json> <out.json> "
					+ "<mysql-jdbc-url> <schema.table> <terms.tsv>");
			System.exit(2);
		}
		Path pairsFile = Path.of(args[0]);
		Path out = Path.of(args[1]);
		String mysqlUrl = args[2];
		String table = args[3];
		Path terms = Path.of(args[4]);

		ObjectMapper mapper = new ObjectMapper();
		JsonNode pairs = mapper.readTree(Files.readString(pairsFile));

		try (Connection mysql = DriverManager.getConnection(mysqlUrl)) {
			raiseRegexpTimeLimit(mysql);
			long exported = exportTerms(mysql, table, terms);
			System.out.printf("  exported %d terms from %s%n", exported, table);

			try (Connection duck = DriverManager.getConnection("jdbc:duckdb:")) {
				long loaded = loadTerms(duck, terms);
				if (loaded != exported) {
					throw new IllegalStateException("DuckDB loaded " + loaded
							+ " of " + exported + " terms - the comparison would be "
							+ "against different data");
				}
				System.out.printf("  DuckDB read %d of them%n", loaded);
				ArrayNode results = mapper.createArrayNode();
				int i = 0;
				for (JsonNode pair : pairs) {
					results.add(compare(mapper, mysql, duck, table, pair, ++i,
							pairs.size()));
				}
				ObjectNode root = mapper.createObjectNode();
				root.put("terms", exported);
				root.put("table", table);
				root.set("calls", results);
				Files.writeString(out, mapper.writerWithDefaultPrettyPrinter()
						.writeValueAsString(root));
				System.out.printf("  wrote %s%n", out);
			}
		}
	}

	/**
	 * MySQL aborts a regex that exceeds {@code regexp_time_limit} steps per row,
	 * which the AMT patterns do at the default 32 - as an ERROR, so it cannot be
	 * mistaken for zero rows, but it does stop the comparison.
	 */
	private static void raiseRegexpTimeLimit(Connection mysql) throws SQLException {
		try (Statement st = mysql.createStatement()) {
			st.execute("SET GLOBAL regexp_time_limit = 100000");
		} catch (SQLException e) {
			System.out.printf("  WARNING: could not raise regexp_time_limit (%s) - "
					+ "long patterns may error%n", e.getMessage());
		}
	}

	/**
	 * id and term, tab-separated. Lossless because RF2 uses tab as its field
	 * separator, so no term contains one.
	 */
	private static long exportTerms(Connection mysql, String table, Path terms)
			throws SQLException, IOException {
		long rows = 0;
		try (PreparedStatement ps = mysql.prepareStatement(
				"SELECT id, term FROM " + table + " WHERE active = 1");
				BufferedWriter w = Files.newBufferedWriter(terms, StandardCharsets.UTF_8)) {
			ps.setFetchSize(Integer.MIN_VALUE);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					w.write(rs.getString(1));
					w.write('\t');
					w.write(rs.getString(2).replace('\t', ' ').replace('\n', ' '));
					w.write('\n');
					rows++;
				}
			}
		}
		return rows;
	}

	private static long loadTerms(Connection duck, Path terms) throws SQLException {
		try (Statement st = duck.createStatement()) {
			st.execute("CREATE TABLE terms AS SELECT * FROM read_csv('"
					+ terms.toAbsolutePath() + "', delim='\t', quote='', header=false, "
					+ "columns={'id': 'VARCHAR', 'term': 'VARCHAR'})");
			try (ResultSet rs = st.executeQuery("SELECT count(*) FROM terms")) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

	private static ObjectNode compare(ObjectMapper mapper, Connection mysql,
			Connection duck, String table, JsonNode pair, int n, int total)
			throws SQLException {
		String assertion = pair.path("assertion").asText();
		String mysqlPattern = pair.path("mysql_pattern").asText();
		String duckPattern = pair.path("duck_pattern").asText();
		String flags = pair.path("duck_flags").asText("");

		ObjectNode result = mapper.createObjectNode();
		result.put("assertion", assertion);
		result.put("mysql_pattern", mysqlPattern);
		result.put("duck_pattern", duckPattern);
		result.put("duck_flags", flags);
		String collation = pair.path("mysql_collation").asText("binary");
		result.put("mysql_collation", collation);

		TreeSet<String> mysqlIds;
		try {
			mysqlIds = mysqlMatches(mysql, table, mysqlPattern, collation);
		} catch (SQLException e) {
			result.put("verdict", "mysql-error");
			result.put("detail", e.getMessage());
			System.out.printf("  [%3d/%d] %-18s %s%n", n, total, "mysql-error",
					shorten(assertion));
			System.out.printf("           %s%n", e.getMessage());
			return result;
		}
		TreeSet<String> duckIds;
		TreeSet<String> insensitive;
		try {
			duckIds = duckMatches(duck, duckPattern, flags);
			insensitive = duckMatches(duck, duckPattern,
					flags.contains("i") ? flags : flags + "i");
		} catch (SQLException e) {
			// A store pattern DuckDB cannot compile is the worst case this probe
			// can find - the assertion errors at run time and its report reads
			// as incomplete rather than wrong - so it is a verdict, and the run
			// continues to measure the rest.
			result.put("verdict", "duck-error");
			result.put("detail", e.getMessage());
			System.out.printf("  [%3d/%d] %-18s %s%n", n, total, "duck-error",
					shorten(assertion));
			System.out.printf("           %s%n           pattern: %s%n",
					e.getMessage(), shorten(duckPattern, 110));
			return result;
		}

		result.put("mysql_rows", mysqlIds.size());
		result.put("duck_rows", duckIds.size());
		result.put("mysql_digest", digest(mysqlIds));
		result.put("duck_digest", digest(duckIds));

		String verdict;
		String detail;
		if (mysqlIds.equals(duckIds)) {
			verdict = mysqlIds.isEmpty() ? "identical-empty" : "identical";
			detail = mysqlIds.size() + " rows";
		} else if (mysqlIds.equals(insensitive)) {
			// The reason this probe exists.
			verdict = "case-sensitivity";
			detail = "MySQL " + mysqlIds.size() + ", DuckDB " + duckIds.size()
					+ "; identical with 'i' (" + insensitive.size() + ")";
		} else {
			verdict = "differs";
			detail = "MySQL " + mysqlIds.size() + " vs DuckDB " + duckIds.size()
					+ "; only MySQL " + sample(mysqlIds, duckIds)
					+ ", only DuckDB " + sample(duckIds, mysqlIds);
		}
		result.put("verdict", verdict);
		result.put("detail", detail);
		System.out.printf("  [%3d/%d] %-18s %s%n", n, total, verdict, shorten(assertion));
		if (!verdict.startsWith("identical")) {
			System.out.printf("           %s%n           pattern: %s%n", detail,
					shorten(mysqlPattern, 110));
		}
		return result;
	}

	/**
	 * The rows MySQL selects, UNDER THE OPERAND'S COLLATION.
	 *
	 * <p>Not a detail: `term` is declared `collate utf8_bin`, so `term REGEXP
	 * 'artifact'` does not match "Artifact due to freezing" - while the same
	 * pattern against `get_cr_ADRS_PT(id)`, whose return carries the schema
	 * default `utf8mb4_0900_ai_ci`, does. Evaluating every pattern against the
	 * column reports the 'i'-flagged calls as DuckDB matching a superset, which
	 * measures the harness rather than the transpilation.
	 */
	private static TreeSet<String> mysqlMatches(Connection mysql, String table,
			String pattern, String collation) throws SQLException {
		String operand = "binary".equals(collation)
				? "term"
				: "CONVERT(term USING utf8mb4) COLLATE " + collation;
		TreeSet<String> ids = new TreeSet<>();
		try (PreparedStatement ps = mysql.prepareStatement(
				"SELECT id FROM " + table + " WHERE active = 1 AND "
						+ operand + " REGEXP ?")) {
			ps.setString(1, pattern);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					ids.add(rs.getString(1));
				}
			}
		}
		return ids;
	}

	private static TreeSet<String> duckMatches(Connection duck, String pattern,
			String flags) throws SQLException {
		TreeSet<String> ids = new TreeSet<>();
		String sql = flags.isEmpty()
				? "SELECT id FROM terms WHERE regexp_matches(term, ?)"
				: "SELECT id FROM terms WHERE regexp_matches(term, ?, ?)";
		try (PreparedStatement ps = duck.prepareStatement(sql)) {
			ps.setString(1, pattern);
			if (!flags.isEmpty()) {
				ps.setString(2, flags);
			}
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					ids.add(rs.getString(1));
				}
			}
		}
		return ids;
	}

	private static List<String> sample(TreeSet<String> from, TreeSet<String> without) {
		List<String> out = new ArrayList<>();
		for (String id : from) {
			if (!without.contains(id)) {
				out.add(id);
				if (out.size() == SAMPLE) {
					break;
				}
			}
		}
		return out;
	}

	/** sha256 of the sorted id set - the ids themselves are not the finding. */
	private static String digest(TreeSet<String> ids) {
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			for (String id : ids) {
				sha.update(id.getBytes(StandardCharsets.UTF_8));
				sha.update((byte) '\n');
			}
			return HexFormat.of().formatHex(sha.digest()).substring(0, 16);
		} catch (Exception e) {
			throw new IllegalStateException("no SHA-256", e);
		}
	}

	private static String shorten(String s) {
		return shorten(s, 56);
	}

	private static String shorten(String s, int max) {
		String flat = s.replace('\n', ' ').toLowerCase(Locale.ROOT).length() > max
				? s.substring(0, max) : s;
		return flat.replace('\n', ' ');
	}
}
