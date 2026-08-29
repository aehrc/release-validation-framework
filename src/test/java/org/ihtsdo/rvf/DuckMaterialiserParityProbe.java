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
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.ihtsdo.rvf.core.service.duck.DuckMaterialiser;

/**
 * Does DuckMaterialiser produce the same tables as the Python materialiser?
 *
 * <p>Running the assertion corpus over both is a necessary check but a weak one:
 * on the AU daily build only 3 of the 200 assertions return any findings, so a
 * table that only the other 197 touch could be loaded wrongly and the totals
 * would still match. This compares every table directly - row count and an
 * order-independent content hash - so a difference in any column of any of the
 * 66 tables shows up whether an assertion happens to read it or not.
 *
 * <pre>
 *   DuckMaterialiserParityProbe &lt;store.json&gt; &lt;parquet-dir&gt; &lt;rf2-release-dir&gt;
 * </pre>
 */
public class DuckMaterialiserParityProbe {

	public static void main(String[] args) throws Exception {
		if (args.length < 3) {
			System.err.println("usage: DuckMaterialiserParityProbe <store.json> <parquet-dir> <rf2-release-dir>");
			System.exit(64);
		}
		Path store = Path.of(args[0]);
		Path parquetDir = Path.of(args[1]);
		Path releaseDir = Path.of(args[2]);
		Map<String, String> tableColumns = tableColumns(Files.readString(store));

		Class.forName("org.duckdb.DuckDBDriver");
		try (Connection con = DriverManager.getConnection("jdbc:duckdb:")) {
			try (Statement st = con.createStatement()) {
				st.execute("CREATE SCHEMA py");
				try (var files = Files.list(parquetDir)) {
					for (Path p : files.filter(f -> f.toString().endsWith(".parquet")).toList()) {
						String t = p.getFileName().toString().replaceFirst("\\.parquet$", "");
						st.execute("CREATE VIEW py." + t + " AS SELECT * FROM read_parquet('"
								+ p.toAbsolutePath() + "')");
					}
				}
			}
			var r = DuckMaterialiser.materialise(con, releaseDir, "java", tableColumns);
			System.out.printf("java  : %d tables, %,d rows, %d empty files, %d placeholders, %,dms%n",
					r.tablesLoaded(), r.rows(), r.emptyFiles(), r.placeholders(), r.millis());

			List<String> onlyPy = new ArrayList<>();
			List<String> mismatch = new ArrayList<>();
			int same = 0;
			for (String t : new TreeSet<>(tables(con, "py"))) {
				if (!tables(con, "java").contains(t)) {
					onlyPy.add(t);
					continue;
				}
				String[] py = fingerprint(con, "py", t);
				String[] jv = fingerprint(con, "java", t);
				if (py[0].equals(jv[0]) && py[1].equals(jv[1])) {
					same++;
				} else {
					mismatch.add(String.format("%-38s py rows=%s hash=%s | java rows=%s hash=%s",
							t, py[0], py[1], jv[0], jv[1]));
				}
			}
			System.out.println();
			System.out.println("IDENTICAL    : " + same + " tables (row count and content hash)");
			System.out.println("MISMATCHED   : " + mismatch.size());
			mismatch.forEach(m -> System.out.println("   " + m));
			System.out.println("MISSING      : " + onlyPy.size() + (onlyPy.isEmpty() ? "" : " " + onlyPy));
			System.exit(mismatch.isEmpty() && onlyPy.isEmpty() ? 0 : 1);
		}
	}

	private static List<String> tables(Connection con, String schema) throws Exception {
		List<String> out = new ArrayList<>();
		try (Statement st = con.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT table_name FROM information_schema.tables WHERE table_schema='"
						+ schema + "'")) {
			while (rs.next()) {
				out.add(rs.getString(1));
			}
		}
		return out;
	}

	/**
	 * Row count plus an order-independent hash of every value in the table.
	 * Order-independent on purpose: the two materialisers sort on the same key
	 * but the probe should not fail over row order, only over content. to_json
	 * renders each row with its column names, so a renamed or reordered column
	 * changes the hash too.
	 */
	private static String[] fingerprint(Connection con, String schema, String table) throws Exception {
		try (Statement st = con.createStatement();
				ResultSet rs = st.executeQuery(
						"SELECT count(*), coalesce(sum(hash(to_json(t))::HUGEINT), 0) FROM "
						+ schema + "." + table + " t")) {
			rs.next();
			return new String[] { rs.getString(1), rs.getString(2) };
		}
	}

	private static Map<String, String> tableColumns(String json) {
		Map<String, String> out = new LinkedHashMap<>();
		int i = json.indexOf("\"tableColumns\"");
		int start = json.indexOf('{', i);
		int depth = 0, end = start;
		for (int k = start; k < json.length(); k++) {
			char c = json.charAt(k);
			if (c == '{') {
				depth++;
			} else if (c == '}' && --depth == 0) {
				end = k;
				break;
			}
		}
		Matcher m = Pattern.compile("\"([a-z0-9_]+)\"\\s*:\\s*\"([^\"]*)\"")
				.matcher(json.substring(start, end + 1));
		while (m.find()) {
			out.put(m.group(1), m.group(2));
		}
		return out;
	}
}
