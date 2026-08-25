package org.ihtsdo.rvf.core.service.duck;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.ihtsdo.rvf.core.service.util.RF2FileTableMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads an RF2 release into DuckDB tables - the DuckDB counterpart of
 * {@code ReleaseFileDataLoader.loadFilesIntoDB}, which does the same job with
 * {@code load data local infile} into a per-run MySQL schema.
 *
 * <p>Two things are deliberately taken from elsewhere rather than restated here:
 *
 * <ul>
 *   <li>the RF2 filename to table mapping comes from RVF's own
 *       {@link RF2FileTableMapper}. The Python materialiser had to port that
 *       class, and a port is a copy that drifts; in Java it is simply the same
 *       code the MySQL path uses.</li>
 *   <li>the column names and DuckDB types come from the precompiled store's
 *       {@code tableColumns}, which is derived from RVF's own
 *       create-tables-mysql.sql at publish time. Notably this is what keeps
 *       SCTIDs as BIGINT: read with inferred or all-VARCHAR types, every id
 *       comparison routes through DOUBLE and silently stops matching above
 *       2^53, which is 12% of concepts and 34% of relationships.</li>
 * </ul>
 *
 * <p>A table with no {@code tableColumns} entry is not materialisable and is
 * skipped rather than reported missing: the 13 {@code *_active} relations are
 * built by pre-requisites.sql at run time, not loaded from a file.
 */
public final class DuckMaterialiser {

	private static final Logger LOGGER = LoggerFactory.getLogger(DuckMaterialiser.class);

	/**
	 * The dominant join key per table. Correctness does not depend on this, but
	 * DuckDB keeps per-row-group min/max statistics, so loading in join-key
	 * order gives the pruning that the MyISAM schema needed a B-tree index for.
	 * Mirrors the Python materialiser, whose output this must reproduce.
	 */
	private static final Map<String, String> SORT_KEY = Map.of(
			"concept_s", "id",
			"description_s", "conceptid",
			"textdefinition_s", "conceptid",
			"relationship_s", "sourceid",
			"stated_relationship_s", "sourceid",
			"relationship_concrete_values_s", "sourceid");
	private static final String DEFAULT_SORT = "referencedcomponentid";

	/**
	 * RF2 has no NULLs and MySQL loads an empty field as an empty string, so
	 * nothing may be treated as NULL. It cannot be omitted (DuckDB's default
	 * would take over) and it must not be the empty string: that turns every
	 * equality join on an optional column - guideurl, parentdomain - into
	 * NULL = NULL, which is never true, manufacturing failures out of nothing.
	 * A four-character literal no RF2 field can hold is the way to say "none".
	 */
	private static final String NO_NULLSTR = "\\x01";

	private DuckMaterialiser() {
	}

	public record Result(int tablesLoaded, int emptyFiles, int placeholders, long rows, long millis) {
	}

	public static Result materialise(Connection con, Path releaseDir, String schema,
			Map<String, String> tableColumns) throws SQLException, IOException {
		long t0 = System.currentTimeMillis();
		Map<String, List<Path>> files = releaseFiles(releaseDir);
		List<String> loaded = new ArrayList<>();
		int emptyFiles = 0;
		long rows = 0;

		try (Statement st = con.createStatement()) {
			st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);

			for (Map.Entry<String, List<Path>> e : files.entrySet()) {
				String table = e.getKey();
				String columns = tableColumns.get(table);
				if (columns == null) {
					// Mapped by name but not declared by the DDL. Nothing can
					// query it, so loading it would only cost time.
					LOGGER.debug("no column spec for {} - not materialised", table);
					continue;
				}
				// A zero-byte RF2 file has no header line for read_csv to
				// consume, so it raises and takes the whole load down with it.
				// MySQL loads it as zero rows and carries on. Matching that is
				// the right call - an empty file is a fact about the release,
				// not a reason to refuse to validate it - and it is not
				// hypothetical: fix-long-terms.sh empties any description file
				// with no term at or over the length limit, which is every AU
				// daily build's sct2_Description_Delta-en.
				List<Path> loadable = new ArrayList<>();
				for (Path file : e.getValue()) {
					if (Files.size(file) == 0) {
						LOGGER.info("empty file {} - not loaded into {}",
								file.getFileName(), table);
						emptyFiles++;
					} else {
						loadable.add(file);
					}
				}
				if (loadable.isEmpty()) {
					// Every file for this table was empty; the placeholder pass
					// below still gives it a queryable, zero-row table.
					continue;
				}
				rows += load(st, schema, table, loadable, columns);
				loaded.add(table);
			}

			// RVF creates the whole table set - delta, full AND snapshot -
			// whether or not the release ships the file, so an assertion over
			// an absent refset meets an empty table. Most such assertions are
			// "no bad rows in X" and pass correctly against zero rows; without
			// the placeholder they die on a missing catalog entry instead,
			// which reads as a failure rather than a pass.
			int placeholders = 0;
			for (Map.Entry<String, String> e : new TreeMap<>(tableColumns).entrySet()) {
				if (loaded.contains(e.getKey())) {
					continue;
				}
				st.execute("CREATE TABLE IF NOT EXISTS " + schema + "." + e.getKey()
						+ " (" + e.getValue() + ")");
				placeholders++;
			}
			Result r = new Result(loaded.size(), emptyFiles, placeholders, rows,
					System.currentTimeMillis() - t0);
			LOGGER.info("materialised {} tables ({} rows), {} empty files, {} placeholders in {}ms",
					r.tablesLoaded(), r.rows(), r.emptyFiles(), r.placeholders(), r.millis());
			return r;
		}
	}

	/**
	 * Loads every file that maps to this table, as ONE relation.
	 *
	 * <p>A list, not a single path, because the mapping is many-to-one and the
	 * releases where it is are not exotic. RVF's own regression fixture for the
	 * Swiss edition ships five Language Snapshot files (-de-ch, -en, -fr-ch,
	 * -fr, -it-ch) and four Description Snapshot files, and every one of them
	 * maps to langrefset_s / description_s. MySQL issues one
	 * {@code load data local infile ... into table} per file, so all of them
	 * land; keying by table and taking one path silently loads the last and
	 * discards the rest, which is four fifths of a release's language refset
	 * missing with nothing anywhere reporting it. An English-only edition never
	 * shows the difference, which is why this survived AU parity testing.
	 *
	 * <p>read_csv takes the list directly rather than this appending file by
	 * file, so the ORDER BY still sorts the whole relation - loading the first
	 * file sorted and inserting the others after it would leave DuckDB's
	 * row-group statistics useless for pruning.
	 */
	private static long load(Statement st, String schema, String table, List<Path> files,
			String columns) throws SQLException {
		String spec = columnSpec(columns);
		String sort = sortKey(table, columns);
		String fileList = files.stream()
				.map(f -> "'" + f.toAbsolutePath() + "'")
				.collect(java.util.stream.Collectors.joining(", "));
		String sql = "CREATE OR REPLACE TABLE " + schema + "." + table + " AS SELECT * FROM read_csv(["
				+ fileList + "], delim='\t', header=true, columns={" + spec + "}, "
				// RF2 is not quoted or escaped. Leaving DuckDB's defaults in
				// place makes a term containing a double quote swallow the rest
				// of the line.
				+ "quote='', escape='', nullstr='" + NO_NULLSTR + "', "
				// Never skip a bad row. A silently dropped line is a validation
				// that reports fewer failures than the release actually has.
				+ "ignore_errors=false) ORDER BY " + sort;
		st.execute(sql);
		try (var rs = st.executeQuery("SELECT count(*) FROM " + schema + "." + table)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	/** {@code "id BIGINT, active VARCHAR"} to {@code "'id':'BIGINT','active':'VARCHAR'"}. */
	private static String columnSpec(String columns) {
		List<String> parts = new ArrayList<>();
		for (String col : columns.split(",")) {
			String[] nameType = col.trim().split("\\s+", 2);
			parts.add("'" + nameType[0] + "':'" + nameType[1] + "'");
		}
		return String.join(",", parts);
	}

	private static String sortKey(String table, String columns) {
		List<String> names = new ArrayList<>();
		for (String col : columns.split(",")) {
			names.add(col.trim().split("\\s+", 2)[0]);
		}
		String preferred = SORT_KEY.getOrDefault(table, DEFAULT_SORT);
		return names.contains(preferred) ? preferred : names.get(0);
	}

	/**
	 * Every RF2 .txt under the release, grouped by the table RVF would load it
	 * into. Sorted so the load order - and therefore any failure - is
	 * reproducible run to run.
	 *
	 * <p>A list per table, because the RF2-name-to-table mapping is many-to-one:
	 * the language and description files carry a language suffix that the mapper
	 * matches with a wildcard, so a multilingual edition has several files per
	 * table. See {@link #load}.
	 */
	static Map<String, List<Path>> releaseFiles(Path releaseDir) throws IOException {
		Map<String, List<Path>> out = new LinkedHashMap<>();
		try (Stream<Path> walk = Files.walk(releaseDir)) {
			List<Path> txt = walk.filter(Files::isRegularFile)
					.filter(p -> p.getFileName().toString().endsWith(".txt"))
					.sorted()
					.toList();
			for (Path p : txt) {
				String table = RF2FileTableMapper.getLegacyTableName(p.getFileName().toString());
				if (table != null) {
					out.computeIfAbsent(table, t -> new ArrayList<>()).add(p);
				}
			}
		}
		return out;
	}
}
