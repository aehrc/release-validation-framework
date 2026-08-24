package org.ihtsdo.rvf;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Can the JAVA engine execute the assertion corpus against DuckDB?
 *
 * <p>The DuckDB engine's only irreducibly-Python part is transpilation: sqlglot
 * parses the MySQL assertion, rewrites it, and prints DuckDB. Everything after
 * that is substituting a run's values into sentinels. {@code duck/publish_store.py}
 * does the first half once, at publish time, and writes the statements plus the
 * sentinel table to JSON; this probe does the second half in Java and executes
 * the result over DuckDB's JDBC driver.
 *
 * <p>If this works, no transpiler needs to exist in Java - which is what makes a
 * Java+DuckDB RVF tractable rather than a rewrite of rvfsql.py.
 *
 * <p>Deliberately dependency-free: no Jackson, no Spring, no RVF wiring. It is a
 * feasibility probe, and a failure here should be attributable to DuckDB or to
 * the store, not to a framework in between.
 *
 * <pre>
 *   DuckStoreProbe &lt;store.json&gt; &lt;parquet-dir&gt; [assertion-limit]
 * </pre>
 */
public class DuckStoreProbe {

	/** Schemas actually materialised, so bind() only points at ones that exist. */
	private static final java.util.Set<String> SCHEMAS = new java.util.HashSet<>();

	public static void main(String[] args) throws Exception {
		if (args.length < 2) {
			System.err.println("usage: DuckStoreProbe <store.json> <parquet-dir|rf2-release-dir> [limit]");
			System.exit(64);
		}
		Path storePath = Path.of(args[0]);
		Path parquetDir = Path.of(args[1]);
		int limit = args.length > 2 ? Integer.parseInt(args[2]) : Integer.MAX_VALUE;

		String json = Files.readString(storePath);
		Map<String, String> sentinels = sentinels(json);
		List<String[]> assertions = selectGroups(assertions(json), json);
		List<String[]> prerequisites = prerequisites(json);
		System.out.println("store        : " + storePath);
		System.out.println("sentinels    : " + sentinels.size());
		System.out.println("assertions   : " + assertions.size());
		System.out.println("prerequisites: " + prerequisites.size() + " statement(s)");

		Class.forName("org.duckdb.DuckDBDriver");
		try (Connection con = DriverManager.getConnection("jdbc:duckdb:")) {
			// Two ways in, and the point of having both is that everything
			// downstream is identical. Parquet is what the Python materialiser
			// produces and what this probe was first proven against;
			// DuckMaterialiser reads the RF2 release itself. Same store, same
			// binder, same assertions - so any difference in the result is the
			// materialiser and nothing else.
			if (hasParquet(parquetDir)) {
				attach(con, parquetDir, json);
			} else {
				materialise(con, parquetDir, json, "prospective");
			}
			// 81 of the 453 assertions in the full corpus read the PREVIOUS
			// release and 20 read the DEPENDENCY - prev_x and dependency_x in
			// the source SQL, rewritten to <PREVIOUS>.x and <DEPENDENCY>.x at
			// import time. They are separate schemas, so they are separate
			// materialisations into the names the store's sentinels already use.
			// Without them those assertions fail on a missing table rather than
			// reporting anything.
			for (String[] extra : new String[][] {
					{ "previous", System.getProperty("probe.previous", "") },
					{ "dependency", System.getProperty("probe.dependency", "") } }) {
				if (!extra[1].isBlank()) {
					materialise(con, Path.of(extra[1]), json, extra[0]);
				}
			}
			createResultTable(con);

			int applied = 0, prereqFailed = 0;
			List<String> prereqErrors = new ArrayList<>();
			for (String[] p : prerequisites) {
				try (Statement st = con.createStatement()) {
					st.execute(bind(p[1], sentinels, 1, "prereq"));
					applied++;
				} catch (Exception e) {
					// A MySQL CREATE FUNCTION body has no DuckDB equivalent and
					// is expected to fail; anything else here is a real problem,
					// so keep the message rather than counting it silently.
					prereqFailed++;
					if (prereqErrors.size() < 6) {
						prereqErrors.add(p[1].substring(0, Math.min(70, p[1].length()))
								.replace('\n', ' ') + "\n        " + e.getMessage().replace('\n', ' '));
					}
				}
			}
			System.out.println("prereqs      : " + applied + " applied, " + prereqFailed + " skipped");
			for (String e : prereqErrors) {
				System.out.println("  ~ " + e);
			}

			// The ports must follow the pre-requisites and precede the
			// assertions: they supply the *_active relations and the helper
			// macros that stand in for the MySQL functions just skipped. Omit
			// them and every amtv4 assertion fails with "Table with name
			// description_active does not exist", which reads like a broken
			// materialisation and is not.
			int portsOk = 0;
			List<String> portErrors = new ArrayList<>();
			for (String p : ports(json)) {
				try (Statement st = con.createStatement()) {
					st.execute(bind(p, sentinels, 1, "ports"));
					portsOk++;
				} catch (Exception e) {
					portErrors.add(p.split("\\(")[0].trim() + ": " + e.getMessage().replace('\n', ' '));
				}
			}
			System.out.println("ports        : " + portsOk + " installed"
					+ (portErrors.isEmpty() ? "" : ", " + portErrors.size() + " FAILED"));
			for (String e : portErrors.subList(0, Math.min(4, portErrors.size()))) {
				System.out.println("  ! " + e);
			}

			int ok = 0, failed = 0;
			long totalFindings = 0;
			List<String> errors = new ArrayList<>();
			int n = 0;
			for (String[] a : assertions) {
				if (n++ >= limit) {
					break;
				}
				String uuid = a[0], file = a[1], stmt = a[2];
				try (Statement st = con.createStatement()) {
					st.execute(bind(stmt, sentinels, 1, uuid));
					ok++;
				} catch (Exception e) {
					failed++;
					// Keep every failure, not the first 8. On the 200-assertion
					// AU corpus 8 was the whole list; on the full 453 it hid 100
					// of 108, and a truncated list cannot be categorised - which
					// is the only useful thing to do with it.
					errors.add(file + "\t" + e.getMessage().replace('\n', ' '));
				}
			}
			try (Statement st = con.createStatement();
					ResultSet rs = st.executeQuery("SELECT count(*) FROM rvf_results.qa_result")) {
				if (rs.next()) {
					totalFindings = rs.getLong(1);
				}
			}
			// Per-assertion counts, so this can be diffed against rvf_duck.py's
			// report. A total alone cannot distinguish "same answer" from "two
			// offsetting differences".
			String out = System.getProperty("probe.out");
			if (out != null) {
				StringBuilder sb = new StringBuilder("{\n");
				try (Statement st = con.createStatement();
						ResultSet rs = st.executeQuery(
								"SELECT assertion_id, count(*) FROM rvf_results.qa_result "
								+ "GROUP BY assertion_id ORDER BY assertion_id")) {
					boolean first = true;
					while (rs.next()) {
						if (!first) {
							sb.append(",\n");
						}
						first = false;
						sb.append(" \"").append(rs.getString(1)).append("\": ").append(rs.getLong(2));
					}
				}
				sb.append("\n}\n");
				Files.writeString(Path.of(out), sb.toString());
				System.out.println("wrote        : " + out);
			}

			System.out.println();
			System.out.println("EXECUTED     : " + ok);
			System.out.println("FAILED       : " + failed);
			System.out.println("FINDINGS     : " + totalFindings + " rows in qa_result");
			String errOut = System.getProperty("probe.errors");
			if (errOut != null) {
				Files.writeString(Path.of(errOut), String.join("\n", errors) + "\n");
				System.out.println("errors       : " + errOut + " (" + errors.size() + " rows)");
			}
			for (String e : errors.subList(0, Math.min(8, errors.size()))) {
				System.out.println("  ! " + e.replace("\t", "\n      "));
			}
			System.exit(failed == 0 ? 0 : 1);
		}
	}

	/**
	 * Attach the Parquet datasets under the names the SQL expects.
	 *
	 * <p>The schema names are NOT release names: precompile()'s sentinels bind to
	 * the literal strings prospective/previous/dependency, matching how
	 * rvf_duck.py attaches. A run with no previous release leaves those
	 * placeholders unbound on purpose, so assertions referencing them fail the
	 * same way they do on MySQL rather than silently reading the wrong schema.
	 */
	/**
	 * Session settings both entry paths need. Neither is optional and
	 * neither carries to another connection, so anything that opens its own
	 * must repeat them - rvf_duck.py sets the same two per cursor.
	 */
	private static void session(Connection con) throws Exception {
		try (Statement st = con.createStatement()) {
			// pre-requisites.sql refers to its inputs UNQUALIFIED - "FROM
			// concept_s", not "FROM prospective.concept_s" - so the connection
			// needs a default schema or every one of them fails with "Table with
			// name concept_s does not exist! Did you mean prospective.concept_s?"
			// rvf_duck.py sets the same thing, and notes it does not carry to a
			// new cursor, so anything creating its own connection must repeat it.
			st.execute("SET search_path='prospective'");
			// MySQL casts freely between types; DuckDB does not. The amtv4 macro
			// isValidComponentId_cr calls length() on its argument, and
			// referencedcomponentid is BIGINT in the DDL and in the Parquet, so
			// without this the call fails with "No function matches the given
			// name and argument types 'length(BIGINT)'" - one assertion out of
			// 200, which is easy to mistake for a content or macro problem.
			// rvf_duck.py sets the same flag per cursor for the same reason.
			st.execute("SET old_implicit_casting=true");
		}
	}

	/**
	 * Keep only the assertions the requested groups select.
	 *
	 * <p>RVF never runs a corpus whole: the invocation names groups, and
	 * groups.xml plus policies.xml decide membership. Production's AU run asks
	 * for nine of the forty-odd groups. Run all 453 files instead and you run
	 * ten other countries' preferred-term assertions, which on an AU release was
	 * 84% of every finding - so this is not a refinement, it is the difference
	 * between a number that means something and one that does not.
	 *
	 * <p>The rules come from RVF's own AssertionGroupImporter rather than a
	 * reimplementation here. -Dprobe.groups=ALL opts out.
	 */
	private static List<String[]> selectGroups(List<String[]> all, String json) throws Exception {
		String wanted = System.getProperty("probe.groups",
				"common-edition,file-centric-validation,component-centric-validation,"
				+ "release-type-validation,mdrs,common-authoring,au-authoring,"
				+ "AustralianEdition,amtv4");
		String corpus = System.getProperty("probe.corpus", "");
		if ("ALL".equals(wanted) || corpus.isBlank()) {
			System.out.println("groups       : not filtered"
					+ (corpus.isBlank() ? " (no -Dprobe.corpus)" : ""));
			return all;
		}
		Map<String, String[]> meta = assertionMeta(json);
		List<org.ihtsdo.rvf.core.data.model.Assertion> models = new ArrayList<>();
		for (Map.Entry<String, String[]> e : meta.entrySet()) {
			var a = new org.ihtsdo.rvf.core.data.model.Assertion();
			a.setUuid(java.util.UUID.fromString(e.getKey()));
			a.setAssertionText(e.getValue()[1]);
			a.setKeywords(e.getValue()[2]);
			models.add(a);
		}
		Map<String, java.util.Set<String>> resolved;
		try (var g = Files.newInputStream(Path.of(corpus, "groups.xml"));
				var p = Files.newInputStream(Path.of(corpus, "policies.xml"))) {
			resolved = new org.ihtsdo.rvf.importer.AssertionGroupImporter(null)
					.resolveGroups(g, p, models);
		}
		java.util.Set<String> want = new java.util.LinkedHashSet<>(
				List.of(wanted.split("\\s*,\\s*")));
		java.util.Set<String> keep = new java.util.HashSet<>();
		for (Map.Entry<String, java.util.Set<String>> e : resolved.entrySet()) {
			if (e.getValue().stream().anyMatch(want::contains)) {
				keep.add(e.getKey());
			}
		}
		// The 'resource' category is infrastructure, not validation: those
		// assertions build the shared intermediate tables (res_edited_active_concepts,
		// tmp_pt, ancestors, description_tmp) and define the procedures that other
		// assertions call. They are not in any of the nine groups, so filtering by
		// group alone drops them and 12 assertions then fail on a missing table
		// that nothing was left to create. Keep them, and run them FIRST.
		java.util.Set<String> resource = new java.util.HashSet<>();
		for (Map.Entry<String, String[]> e : meta.entrySet()) {
			if (java.util.Arrays.stream(e.getValue()[2].split(","))
					.map(String::trim).anyMatch("resource"::equals)) {
				resource.add(e.getKey());
			}
		}
		List<String[]> first = all.stream().filter(a -> resource.contains(a[0])).toList();
		List<String[]> rest = all.stream()
				.filter(a -> keep.contains(a[0]) && !resource.contains(a[0])).toList();
		List<String[]> out = new ArrayList<>(first);
		out.addAll(rest);
		System.out.println("groups       : " + want.size() + " requested, "
				+ keep.size() + " of " + meta.size() + " assertions selected; "
				+ resource.size() + " resource assertions run first ("
				+ out.size() + " statements)");
		return out;
	}

	private static boolean hasParquet(Path dir) throws Exception {
		try (var files = Files.list(dir)) {
			return files.anyMatch(f -> f.toString().endsWith(".parquet"));
		}
	}

	private static void materialise(Connection con, Path releaseDir, String json, String schema)
			throws Exception {
		var r = org.ihtsdo.rvf.core.service.duck.DuckMaterialiser.materialise(
				con, releaseDir, schema, tableColumns(json));
		SCHEMAS.add(schema);
		session(con);
		System.out.println("materialised : " + r.tablesLoaded() + " tables, " + r.rows()
				+ " rows, " + r.emptyFiles() + " empty files, " + r.placeholders()
				+ " placeholders in " + r.millis() + "ms, as schema '" + schema + "'");
	}

	private static void attach(Connection con, Path parquetDir, String json) throws Exception {
		try (Statement st = con.createStatement()) {
			st.execute("CREATE SCHEMA IF NOT EXISTS prospective");
			SCHEMAS.add("prospective");
			int views = 0;
			try (var files = Files.list(parquetDir)) {
				for (Path p : files.filter(f -> f.toString().endsWith(".parquet")).toList()) {
					String table = p.getFileName().toString().replaceFirst("\\.parquet$", "");
					st.execute("CREATE OR REPLACE VIEW prospective." + table
							+ " AS SELECT * FROM read_parquet('" + p.toAbsolutePath() + "')");
					views++;
				}
			}
			// Every DDL table the release does not ship gets an EMPTY
			// placeholder, exactly as rvf_duck.py's attach() does. Most
			// assertions over an absent table are "no bad rows in X" and pass
			// correctly against zero rows; without the placeholder they die on a
			// missing catalog entry instead, which is a false failure.
			session(con);
			int placeholders = 0;
			for (Map.Entry<String, String> e : tableColumns(json).entrySet()) {
				try (ResultSet rs = con.getMetaData().getTables(null, "prospective", e.getKey(), null)) {
					if (rs.next()) {
						continue;
					}
				}
				st.execute("CREATE TABLE IF NOT EXISTS prospective." + e.getKey()
						+ " (" + e.getValue() + ")");
				placeholders++;
			}
			System.out.println("attached     : " + views + " parquet tables, "
					+ placeholders + " empty placeholders, as schema 'prospective'");
		}
	}

	private static void createResultTable(Connection con) throws Exception {
		try (Statement st = con.createStatement()) {
			st.execute("CREATE SCHEMA IF NOT EXISTS rvf_results");
			// Mirrors RVF's qa_result, including skip_module_check (upstream
			// 051e87e) so an assertion naming that column can insert into it.
			st.execute("CREATE TABLE rvf_results.qa_result ("
					+ "id BIGINT, run_id BIGINT, assertion_id VARCHAR, concept_id BIGINT, "
					+ "details VARCHAR, component_id VARCHAR, table_name VARCHAR, "
					+ "skip_module_check BOOLEAN)");
		}
	}

	/**
	 * The Java half of bind(): textual substitution, nothing more.
	 *
	 * <p>The sentinel strings come from the store rather than being written here.
	 * Hardcoding "rvfph_prospective_" would be a copy of a Python private that
	 * drifts the first time it changes, and the failure would be a wrong query
	 * that still runs.
	 */
	private static String bind(String stmt, Map<String, String> sentinels, long runId, String assertionId) {
		String s = stmt;
		for (Map.Entry<String, String> e : sentinels.entrySet()) {
			String placeholder = e.getKey(), sentinel = e.getValue();
			String value = switch (placeholder) {
				case "<RUNID>" -> String.valueOf(runId);
				case "<ASSERTIONUUID>" -> assertionId;
				case "<PROSPECTIVE>", "<TEMP>" -> "prospective";
				// Bound only when that release was actually materialised. Left as
				// the literal placeholder otherwise, exactly as bind() does: an
				// assertion needing a release we do not have must fail, not
				// silently query the prospective one. 81 of the full corpus's
				// 453 assertions read PREVIOUS and 20 read DEPENDENCY, so on the
				// full corpus this is the difference between 108 failures and 28.
				case "<PREVIOUS>" -> SCHEMAS.contains("previous") ? "previous" : "<PREVIOUS>";
				case "<DEPENDENCY>" -> SCHEMAS.contains("dependency") ? "dependency" : "<DEPENDENCY>";
				case "<INCLUDED_MODULES>" -> "NULL";
				default -> "";
			};
			s = s.replace(sentinel, value);
		}
		return s.replaceAll("\\bqa_result\\b", "rvf_results.qa_result");
	}

	// ---- minimal JSON reading -------------------------------------------
	// Enough for this store's shape, and no more. A real implementation uses
	// Jackson, which RVF already depends on; the probe avoids it so that a
	// failure is attributable to DuckDB or the store rather than to binding.

	// ---------------------------------------------------------------------
	// Store parsing.
	//
	// This was hand-rolled regex over the JSON, and adding three fields to each
	// assertion entry silently broke it - the pattern required "sha256" to be
	// followed immediately by "statements", so it matched nothing and the probe
	// would have reported 0 assertions rather than an error. Jackson is already
	// on the classpath; the store is not big enough for streaming to matter.
	// ---------------------------------------------------------------------

	private static com.fasterxml.jackson.databind.JsonNode store(String json) {
		try {
			return new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
		} catch (Exception e) {
			throw new IllegalStateException("store is not valid JSON", e);
		}
	}

	private static Map<String, String> sentinels(String json) {
		Map<String, String> out = new LinkedHashMap<>();
		for (var n : store(json).path("sentinels")) {
			out.put(n.path("placeholder").asText(), n.path("sentinel").asText());
		}
		return out;
	}

	/** {@code [uuid, file, statement]} per statement, in store order. */
	private static List<String[]> assertions(String json) {
		List<String[]> out = new ArrayList<>();
		var node = store(json).path("assertions");
		node.fieldNames().forEachRemaining(uuid -> {
			var a = node.path(uuid);
			for (var s : a.path("statements")) {
				out.add(new String[] { uuid, a.path("file").asText(), s.asText() });
			}
		});
		return out;
	}

	/** {@code uuid -> [file, text, keywords]}, for group resolution. */
	private static Map<String, String[]> assertionMeta(String json) {
		Map<String, String[]> out = new LinkedHashMap<>();
		var node = store(json).path("assertions");
		node.fieldNames().forEachRemaining(uuid -> out.put(uuid, new String[] {
				node.path(uuid).path("file").asText(),
				node.path(uuid).path("text").asText(""),
				node.path(uuid).path("keywords").asText("") }));
		return out;
	}

	private static Map<String, String> tableColumns(String json) {
		Map<String, String> out = new LinkedHashMap<>();
		var node = store(json).path("tableColumns");
		node.fieldNames().forEachRemaining(t -> out.put(t, node.path(t).asText()));
		return out;
	}

	private static List<String> ports(String json) {
		List<String> out = new ArrayList<>();
		for (var n : store(json).path("ports")) {
			out.add(n.asText());
		}
		return out;
	}

	/** {@code [file, statement]} per pre-requisite statement. */
	private static List<String[]> prerequisites(String json) {
		List<String[]> out = new ArrayList<>();
		for (var p : store(json).path("prerequisites")) {
			for (var s : p.path("statements")) {
				out.add(new String[] { p.path("file").asText(), s.asText() });
			}
		}
		return out;
	}

	private static String unescape(String s) {
		return s.replace("\\n", "\n").replace("\\t", "\t").replace("\\r", "\r")
				.replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\");
	}
}
