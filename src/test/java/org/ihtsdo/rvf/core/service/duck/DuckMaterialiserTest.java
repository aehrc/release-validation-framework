package org.ihtsdo.rvf.core.service.duck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the two things about materialisation that are easy to get wrong and
 * expensive to get wrong. Whole-corpus parity against the Python materialiser is
 * proven separately by DuckMaterialiserParityProbe; this is the part worth
 * having in the build.
 */
class DuckMaterialiserTest {

	private static final Map<String, String> COLUMNS = new LinkedHashMap<>();
	static {
		COLUMNS.put("concept_s", "id BIGINT, effectivetime VARCHAR, active VARCHAR, "
				+ "moduleid BIGINT, definitionstatusid BIGINT");
		COLUMNS.put("description_d", "id BIGINT, effectivetime VARCHAR, active VARCHAR, "
				+ "moduleid BIGINT, conceptid BIGINT, languagecode VARCHAR, typeid BIGINT, "
				+ "term VARCHAR, casesignificanceid BIGINT");
	}

	@Test
	void emptyRf2FileBecomesAnEmptyTableRatherThanAFailedLoad(@TempDir Path release) throws Exception {
		// fix-long-terms.sh empties any description file with no term at or over
		// the length limit, which is every AU daily build's
		// sct2_Description_Delta-en. A zero-byte file has no header for
		// read_csv to consume, so without the size check it raises and takes the
		// whole release down with it - where MySQL loads zero rows and carries
		// on. An empty file is a fact about the release, not a reason to refuse
		// to validate it.
		write(release, "Snapshot/Terminology/sct2_Concept_Snapshot_XX1000000_20260831.txt",
				"id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n"
				+ "138875005\t20260831\t1\t900000000000207008\t900000000000074008\n");
		write(release, "Delta/Terminology/sct2_Description_Delta-en_XX1000000_20260831.txt", "");

		try (Connection con = connect()) {
			DuckMaterialiser.Result r = DuckMaterialiser.materialise(con, release, "prospective", COLUMNS);
			assertEquals(1, r.tablesLoaded());
			assertEquals(1, r.emptyFiles());
			assertEquals(1, r.placeholders(), "the emptied file's table still has to exist");
			assertEquals(1, count(con, "concept_s"));
			assertEquals(0, count(con, "description_d"), "empty file, but a queryable table");
		}
	}

	@Test
	void sctidsLoadAsBigintNotVarchar(@TempDir Path release) throws Exception {
		// RVF's MySQL helper functions declared their SCTID parameters as
		// varchar, so id comparisons went via DOUBLE and silently stopped
		// matching above 2^53 - 12% of concepts and 34% of relationships. Typing
		// from the store rather than letting read_csv infer is what stops that
		// coming back, and an inferred load would look fine on small ids.
		write(release, "Snapshot/Terminology/sct2_Concept_Snapshot_XX1000000_20260831.txt",
				"id\teffectiveTime\tactive\tmoduleId\tdefinitionStatusId\n"
				+ "933503021000036108\t20260831\t1\t32506021000036107\t900000000000074008\n");

		try (Connection con = connect()) {
			DuckMaterialiser.materialise(con, release, "prospective", COLUMNS);
			try (Statement st = con.createStatement();
					ResultSet rs = st.executeQuery(
							"SELECT typeof(id), id = 933503021000036108 FROM prospective.concept_s")) {
				assertTrue(rs.next());
				assertEquals("BIGINT", rs.getString(1));
				assertTrue(rs.getBoolean(2), "an SCTID above 2^53 must compare exactly");
			}
		}
	}

	private static Connection connect() throws Exception {
		Class.forName("org.duckdb.DuckDBDriver");
		return DriverManager.getConnection("jdbc:duckdb:");
	}

	private static void write(Path root, String relative, String content) throws Exception {
		Path p = root.resolve(relative);
		Files.createDirectories(p.getParent());
		Files.writeString(p, content);
	}

	private static long count(Connection con, String table) throws Exception {
		try (Statement st = con.createStatement();
				ResultSet rs = st.executeQuery("SELECT count(*) FROM prospective." + table)) {
			rs.next();
			return rs.getLong(1);
		}
	}
}
