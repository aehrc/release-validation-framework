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

	private static final String DESCRIPTION_HEADER =
			"id\teffectiveTime\tactive\tmoduleId\tconceptId\tlanguageCode\ttypeId\tterm\tcaseSignificanceId\n";

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

	@Test
	void everyFileMappingToATableIsLoaded(@TempDir Path release) throws Exception {
		// The RF2-name-to-table mapping is many-to-one, and not rarely: RVF's
		// own regression fixture for the Swiss edition ships five Language
		// Snapshot files and four Description Snapshot files, all of which
		// MySQL loads into the same table with one "load data local infile"
		// each. Keying a release by table name and keeping one path per key
		// loads the last file and discards the rest - four fifths of a
		// language refset gone, with no error anywhere. An English-only
		// edition has one file per table and never shows it.
		write(release, "Snapshot/Terminology/sct2_Description_Snapshot-en_CH1000195_20260831.txt",
				DESCRIPTION_HEADER
				+ "101013\t20260831\t1\t900000000000207008\t138875005\ten\t900000000000003001\tSNOMED CT Concept\t900000000000020002\n");
		write(release, "Snapshot/Terminology/sct2_Description_Snapshot-fr_CH1000195_20260831.txt",
				DESCRIPTION_HEADER
				+ "101014\t20260831\t1\t900000000000207008\t138875005\tfr\t900000000000003001\tConcept SNOMED CT\t900000000000020002\n");

		Map<String, String> columns = Map.of("description_s", COLUMNS.get("description_d"));
		try (Connection con = connect()) {
			DuckMaterialiser.Result r = DuckMaterialiser.materialise(con, release, "prospective", columns);
			assertEquals(1, r.tablesLoaded(), "two files, one table");
			assertEquals(2, r.rows(), "both languages, not just whichever sorted last");
			assertEquals(2, count(con, "description_s"));
		}
	}

	private static Connection connect() throws Exception {
		Class.forName("org.duckdb.DuckDBDriver");
		return DriverManager.getConnection("jdbc:duckdb:");
	}

	/**
	 * The Identifier file ships its columns in a different order from the one
	 * {@code create-tables-mysql.sql} declares - {@code alternateIdentifier}
	 * first, {@code identifierSchemeId} fifth, against a DDL that declares the
	 * scheme first. {@code read_csv}'s {@code columns} parameter binds by
	 * POSITION, so a spec built from the DDL order puts each column's data in
	 * the neighbouring column.
	 *
	 * <p>The values here are chosen so a positional load cannot pass by
	 * coincidence: the scheme id and the referenced component id are different
	 * numbers, and the alternate identifier is numeric, so a swap would load
	 * cleanly rather than raising a conversion error. That is the real hazard -
	 * on a non-numeric alternate identifier DuckDB at least complains.
	 *
	 * <p>MySQL gets this wrong too; see UPSTREAM-SQL-DEFECTS.md defect 8.
	 */
	@Test
	void aFileWhoseColumnOrderDiffersFromTheDdlLoadsByNameNotPosition(@TempDir Path release)
			throws Exception {
		write(release, "Snapshot/Terminology/sct2_Identifier_Snapshot_AU1000036_20260831.txt", """
				alternateIdentifier	effectiveTime	active	moduleId	identifierSchemeId	referencedComponentId
				778899	20260831	1	32506021000036107	705112009	12345
				""");
		Map<String, String> columns = Map.of("identifier_s",
				"identifierschemeid BIGINT, alternateidentifier VARCHAR, effectivetime VARCHAR, "
				+ "active VARCHAR, moduleid BIGINT, referencedcomponentid BIGINT");

		try (Connection con = DriverManager.getConnection("jdbc:duckdb:")) {
			DuckMaterialiser.materialise(con, release, "prospective", columns);
			try (Statement st = con.createStatement();
					ResultSet rs = st.executeQuery("SELECT identifierschemeid, alternateidentifier,"
							+ " moduleid, referencedcomponentid FROM prospective.identifier_s")) {
				assertTrue(rs.next());
				assertEquals(705112009L, rs.getLong("identifierschemeid"),
						"the scheme id must come from the identifierSchemeId column, not the first one");
				assertEquals("778899", rs.getString("alternateidentifier"));
				assertEquals(32506021000036107L, rs.getLong("moduleid"));
				assertEquals(12345L, rs.getLong("referencedcomponentid"));
			}
		}
	}

	/**
	 * AU's extended association refset carries column names the DDL does not
	 * have at all, because RVF pours it into a generic shape positionally. There
	 * is nothing to match by name, so the load stays positional - which is also
	 * what MySQL does. Pinned so that "load by name" does not quietly become
	 * "fail to load anything whose names we do not recognise".
	 */
	@Test
	void aFileWhoseColumnNamesTheDdlDoesNotDeclareStillLoadsPositionally(@TempDir Path release)
			throws Exception {
		write(release, "Snapshot/Refset/Content/der2_ccRefset_ExtendedAssociationSnapshot_AU1000036_20260831.txt", """
				id	effectiveTime	active	moduleId	refsetId	referencedComponentId	targetAdministeredForm	targetManufacturedForm
				a1	20260831	1	32506021000036107	900	111	222	333
				""");
		Map<String, String> columns = Map.of("extendedassociation_s",
				"id VARCHAR, effectivetime VARCHAR, active VARCHAR, moduleid BIGINT, "
				+ "refsetid BIGINT, referencedcomponentid BIGINT, targetcomponentid BIGINT, value BIGINT");

		try (Connection con = DriverManager.getConnection("jdbc:duckdb:")) {
			DuckMaterialiser.materialise(con, release, "prospective", columns);
			try (Statement st = con.createStatement();
					ResultSet rs = st.executeQuery(
							"SELECT targetcomponentid, value FROM prospective.extendedassociation_s")) {
				assertTrue(rs.next());
				assertEquals(222L, rs.getLong("targetcomponentid"));
				assertEquals(333L, rs.getLong("value"));
			}
		}
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
