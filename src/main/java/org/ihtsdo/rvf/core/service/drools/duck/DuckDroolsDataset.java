package org.ihtsdo.rvf.core.service.drools.duck;

import org.ihtsdo.drools.domain.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.snomed.otf.owltoolkit.conversion.AxiomRelationshipConversionService;
import org.snomed.otf.owltoolkit.domain.AxiomRepresentation;
import org.snomed.otf.owltoolkit.domain.Relationship;

import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.duckdb.DuckDBAppender;
import org.duckdb.DuckDBConnection;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Set;

/**
 * A DuckDB view over one or more extracted RF2 snapshot directories, standing in
 * for {@code SnomedDroolsComponentRepository}.
 *
 * <p>The incumbent loads the entire release into JVM heap: every concept,
 * description, relationship and axiom becomes an object in a
 * {@code SnomedDroolsComponentRepository}, plus an in-memory Lucene index over
 * every description term. This reads the same RF2 files with DuckDB and answers
 * the service-layer questions as SQL instead.
 *
 * <h2>Stated relationships are derived, not read</h2>
 *
 * This is the part that cannot be done in SQL alone, and the part a naive port
 * gets silently wrong. Four of {@code ConceptService}'s nine methods depend on
 * <em>stated</em> relationships, and modern RF2 ships no stated relationship
 * file - the stated form lives in the OWL axiom refset. The incumbent converts
 * it at load time in {@code SnomedDroolsComponentFactory}:
 *
 * <pre>
 *   AxiomRepresentation axiom = axiomConverter.convertAxiomToRelationships(owlExpression);
 *   addRelationships(..., axiom.getLeftHandSideNamedConcept(), axiom.getRightHandSideRelationships(), ...);
 * </pre>
 *
 * So we do the same, once, up front: parse the axiom refset with
 * snomed-owl-toolkit and materialise the derived rows into
 * {@code stated_relationship}. Querying an RF2 stated relationship table
 * instead would return empty sets for those four methods on any modern release
 * - and, because they are used as filters, would produce zero violations rather
 * than an error.
 *
 * <p>Everything else is read straight from the RF2 tab-separated files, which
 * DuckDB parses natively.
 */
public class DuckDroolsDataset implements Closeable {

	private static final Logger LOGGER = LoggerFactory.getLogger(DuckDroolsDataset.class);

	/** RF2 columns are read as text. SCTIDs exceed 2^53 and must never go via a float. */
	private static final String READ_OPTS = "delim='\\t', header=true, all_varchar=true, ignore_errors=false";

	private final Connection connection;
	private final String currentEffectiveTime;

	public DuckDroolsDataset(Set<String> rf2SnapshotDirectories, String currentEffectiveTime) throws SQLException {
		this.currentEffectiveTime = currentEffectiveTime;
		this.connection = DriverManager.getConnection("jdbc:duckdb:");
		long start = System.currentTimeMillis();
		createViews(rf2SnapshotDirectories);
		deriveStatedRelationships();
		buildStatedAncestorClosure();
		LOGGER.info("DuckDB Drools dataset ready in {}ms", System.currentTimeMillis() - start);
	}

	public Connection getConnection() {
		return connection;
	}

	public String getCurrentEffectiveTime() {
		return currentEffectiveTime;
	}

	/**
	 * The effectiveTime this edition stamped on its own authoring, read from the
	 * Module Dependency refset.
	 *
	 * <p>This is what makes a delta derivable from the Snapshot alone - no
	 * previous release, no Delta files, no Full files. Content the edition
	 * inherited carries its dependency's older effectiveTime, so it never equals
	 * this value and drops out without needing a module list.
	 *
	 * <p>Deliberately NOT "modules other than core and model". That gives the
	 * same answer for AU but excludes <em>everything</em> for the International
	 * edition, where every module shares one effectiveTime
	 * (449080006 and 900000000000207008 both srcET=tgtET=20260501).
	 *
	 * <p>Verified against the Full file - the true delta being the Full rows
	 * stamped with the release effectiveTime - on International 20260501
	 * (Concept, Description, Relationship, OWLExpression, Language) and AU
	 * 20260831 (Concept, OWLExpression): zero differences.
	 *
	 * @return the edition effectiveTime, or null if there is no module
	 *         dependency refset to read (in which case callers must fall back to
	 *         validating everything rather than silently validating nothing).
	 */
	public String editionEffectiveTime() throws SQLException {
		try (Statement s = connection.createStatement();
			 ResultSet rs = s.executeQuery(
					 "SELECT max(sourceEffectiveTime) FROM module_dependency WHERE active = '1'")) {
			String et = rs.next() ? rs.getString(1) : null;
			if (et == null || et.isEmpty()) {
				LOGGER.warn("No active module dependency rows - cannot derive the edition "
						+ "effectiveTime, so the whole snapshot will be validated.");
				return null;
			}
			return et;
		}
	}

	/**
	 * Globs whose absence means the input is not a release, rather than a
	 * release that omits an optional refset.
	 *
	 * <p>The empty-relation fallback below is correct for something like the
	 * component-annotation refset, which a release need not ship. It is
	 * catastrophic for these: an empty concept view validates nothing and Drools
	 * reports ZERO violations, which is indistinguishable from a clean release.
	 * A symlinked release directory once made all nine views empty and the only
	 * reason anyone noticed was that an unrelated query referenced a column the
	 * placeholder does not have.
	 */
	private static final Set<String> REQUIRED_VIEWS = Set.of("concept", "description_raw");

	/** Temp table holding the ids of the concepts under validation. */
	static final String SCOPE_TABLE = "drools_scope";

	/**
	 * Materialises the validation scope as a table the prefetch queries join to.
	 *
	 * <p>A table, not an {@code IN (...)} list: the scope is thousands of ids on
	 * a real edition, and DuckDB plans a join against a small table far better
	 * than a huge disjunction - quite apart from parameter limits.
	 *
	 * <p>Loaded with the Appender for the same reason
	 * {@code deriveStatedRelationships} uses it: DuckDB's JDBC executeBatch
	 * loops and executes once per row, so it is not a bulk path.
	 */
	void materialiseScope(Collection<String> conceptIds) {
		try (Statement s = connection.createStatement()) {
			s.execute("CREATE OR REPLACE TABLE " + SCOPE_TABLE + " (id VARCHAR)");
		} catch (SQLException e) {
			throw new IllegalStateException("could not create " + SCOPE_TABLE, e);
		}
		try (DuckDBAppender ap = ((DuckDBConnection) connection)
				.createAppender(DuckDBConnection.DEFAULT_SCHEMA, SCOPE_TABLE)) {
			for (String id : conceptIds) {
				ap.beginRow();
				ap.append(id);
				ap.endRow();
			}
			ap.flush();
		} catch (SQLException e) {
			throw new IllegalStateException("could not load " + SCOPE_TABLE, e);
		}
		try (Statement s = connection.createStatement()) {
			s.execute("CREATE INDEX idx_scope_id ON " + SCOPE_TABLE + "(id)");
		} catch (SQLException e) {
			LOGGER.debug("no index on {}: {}", SCOPE_TABLE, e.getMessage());
		}
	}

	/** Runs {@code sql} and hands each row to {@code consumer}. */
	void eachRow(String sql, RowConsumer consumer) {
		try (Statement st = connection.createStatement();
			 ResultSet rs = st.executeQuery(sql)) {
			while (rs.next()) {
				consumer.accept(rs);
			}
		} catch (SQLException e) {
			throw new IllegalStateException("query failed: " + sql, e);
		}
	}

	@FunctionalInterface
	interface RowConsumer {
		void accept(ResultSet rs) throws SQLException;
	}

	private void createViews(Set<String> directories) throws SQLException {
		// A release can be split across several extracted directories (the
		// package itself plus its extension dependency), so every view unions
		// the same glob across all of them.
		try (Statement s = connection.createStatement()) {
			s.execute(view("concept", directories, "**/sct2_Concept_Snapshot*.txt"));
			s.execute(view("description_raw", directories, "**/sct2_Description_Snapshot*.txt"));
			s.execute(view("text_definition_raw", directories, "**/sct2_TextDefinition_Snapshot*.txt"));
			s.execute(view("inferred_relationship", directories, "**/sct2_Relationship_Snapshot*.txt"));
			s.execute(view("language_refset", directories, "**/der2_cRefset_Language*Snapshot*.txt"));
			s.execute(view("association_refset", directories, "**/der2_cRefset_Association*Snapshot*.txt"));
			s.execute(view("owl_refset", directories, "**/sct2_sRefset_OWLExpression*Snapshot*.txt"));
			s.execute(view("module_dependency", directories,
					"**/der2_ssRefset_ModuleDependency*Snapshot*.txt"));
			// Component annotations - Concept.getAnnotations() is new in
			// snomed-drools 6.0.0. A release need not ship this refset, and view()
			// already substitutes an empty relation when the glob matches nothing.
			s.execute(view("annotation_refset", directories,
					"**/der2_scsRefset_ComponentAnnotationStringValue*Snapshot*.txt"));

			// Drools treats text definitions as descriptions carrying a flag,
			// so the two files are unioned into one description view.
			s.execute("CREATE OR REPLACE TABLE description AS "
					+ "SELECT *, false AS is_text_definition FROM description_raw "
					+ "UNION ALL BY NAME "
					+ "SELECT *, true AS is_text_definition FROM text_definition_raw");

			// Indexes for the point-lookup path. Without them each findById is
			// a full scan of the materialised table, which is better than
			// re-reading the CSV but still linear in the edition.
			indexIfPresent(s, "concept", "id");
			// term, because findActiveDescriptionByExactTerm searches the WHOLE
			// release by term - it cannot be scoped, since the question it
			// answers is "does this term exist anywhere else". Sampling put it
			// among the top costs of a scoped run: without this index every call
			// scans ~2.9M descriptions.
			indexIfPresent(s, "description", "term");
			indexIfPresent(s, "description", "id");
            indexIfPresent(s, "description", "conceptId");
			indexIfPresent(s, "inferred_relationship", "sourceId");
			indexIfPresent(s, "language_refset", "referencedComponentId");
			indexIfPresent(s, "association_refset", "referencedComponentId");
		}
	}

	/**
	 * The RF2 columns each optional relation must present when the release does
	 * not ship it.
	 *
	 * <p>An empty placeholder is not enough on its own: it has to have the right
	 * SHAPE. The bulk prefetch selects named columns, so a placeholder carrying
	 * only {@code id} turns a legitimately absent refset into
	 * {@code Binder Error: Table "a" does not have a column named
	 * "referencedComponentId"} - the run dies at prefetch rather than
	 * validating a release that is perfectly valid without that file.
	 *
	 * <p>Component annotations are the live case: the refset is recent, AU ships
	 * it, and plenty of editions do not.
	 */
	private static final Map<String, List<String>> EMPTY_COLUMNS = Map.of(
			"text_definition_raw", List.of("id", "effectiveTime", "active", "moduleId",
					"conceptId", "languageCode", "typeId", "term", "caseSignificanceId"),
			"inferred_relationship", List.of("id", "effectiveTime", "active", "moduleId",
					"sourceId", "destinationId", "relationshipGroup", "typeId",
					"characteristicTypeId", "modifierId"),
			"language_refset", List.of("id", "effectiveTime", "active", "moduleId", "refsetId",
					"referencedComponentId", "acceptabilityId"),
			"association_refset", List.of("id", "effectiveTime", "active", "moduleId", "refsetId",
					"referencedComponentId", "targetComponentId"),
			"owl_refset", List.of("id", "effectiveTime", "active", "moduleId", "refsetId",
					"referencedComponentId", "owlExpression"),
			"module_dependency", List.of("id", "effectiveTime", "active", "moduleId", "refsetId",
					"referencedComponentId", "sourceEffectiveTime", "targetEffectiveTime"),
			"annotation_refset", List.of("id", "effectiveTime", "active", "moduleId", "refsetId",
					"referencedComponentId", "languageDialectCode", "typeId", "value"));

	/**
	 * A zero-row relation with the columns {@code name} would have had.
	 *
	 * <p>Every column is VARCHAR because {@code READ_OPTS} sets
	 * {@code all_varchar}: a placeholder typed differently from the real file
	 * would behave differently in comparisons, which is a worse failure than the
	 * missing column because it is silent.
	 */
	private static String emptyRelation(String name) {
		List<String> columns = EMPTY_COLUMNS.get(name);
		if (columns == null) {
			return "SELECT NULL AS id WHERE false";
		}
		StringBuilder sb = new StringBuilder("SELECT ");
		for (int i = 0; i < columns.size(); i++) {
			sb.append(i == 0 ? "" : ", ")
			  .append("CAST(NULL AS VARCHAR) AS ").append(columns.get(i));
		}
		return sb.append(" WHERE false").toString();
	}

	/**
	 * A view over a glob, unioned across every extracted directory. Missing
	 * files are tolerated: a release legitimately may not ship a given refset,
	 * and DuckDB errors on a glob that matches nothing, so absent globs are
	 * replaced by a correctly-shaped empty relation.
	 */
	/**
	 * Best-effort index. A release that omits a refset leaves an empty
	 * placeholder with only an {@code id} column, so indexing one of its other
	 * columns is a legitimate miss rather than an error.
	 */
	private void indexIfPresent(Statement s, String table, String column) {
		try {
			s.execute("CREATE INDEX idx_" + table + "_" + column.toLowerCase()
					+ " ON " + table + "(" + column + ")");
		} catch (SQLException e) {
			LOGGER.debug("No index on {}({}): {}", table, column, e.getMessage());
		}
	}

	private String view(String name, Set<String> directories, String glob) {
		StringBuilder sb = new StringBuilder();
		for (String dir : directories) {
			Path p = Paths.get(dir);
			if (!matchesAnything(p, glob)) {
				continue;
			}
			if (sb.length() > 0) {
				sb.append(" UNION ALL BY NAME ");
			}
			sb.append("SELECT * FROM read_csv('")
			  .append(p.toAbsolutePath()).append('/').append(glob)
			  .append("', ").append(READ_OPTS).append(')');
		}
		if (sb.length() == 0) {
			if (REQUIRED_VIEWS.contains(name)) {
				throw new IllegalStateException("No files matched " + glob + " in " + directories
						+ ". Without '" + name + "' there is nothing to validate, and Drools "
						+ "would report zero violations - which reads exactly like a clean "
						+ "release. Check the directory really holds an RF2 Snapshot.");
			}
			LOGGER.warn("No files matched {} in {} - '{}' will be empty", glob, directories, name);
			return "CREATE OR REPLACE TABLE " + name + " AS " + emptyRelation(name);
		}
		// A TABLE, not a VIEW. The Drools service interface is point lookups -
		// RuleExecutor.checkComponentsIntegrity calls findById per component -
		// and a view over read_csv re-parses the whole file on EVERY one of
		// them. Materialising once turns 720,000 rows of CSV parsing per lookup
		// into an indexed probe.
		return "CREATE OR REPLACE TABLE " + name + " AS " + sb;
	}

	/**
	 * Deliberately does NOT follow symlinks, because {@code read_csv}'s glob
	 * does not either - a release directory assembled by symlinking in a
	 * Snapshot tree matches nothing on both sides. Keeping the two consistent is
	 * what lets {@link #REQUIRED_VIEWS} turn that case into one clear error
	 * instead of nine empty views and a run that validates nothing.
	 */
	private boolean matchesAnything(Path dir, String glob) {
		try (java.util.stream.Stream<Path> walk = Files.walk(dir)) {
			java.nio.file.PathMatcher m =
					dir.getFileSystem().getPathMatcher("glob:" + dir.toAbsolutePath() + "/" + glob);
			return walk.anyMatch(m::matches);
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Convert the OWL axiom refset into stated relationships, exactly as
	 * {@code SnomedDroolsComponentFactory} does.
	 *
	 * <p>Both orientations are kept, and the GCI flag with them: a regular axiom
	 * has a named concept on the left and relationships on the right, a GCI
	 * axiom the reverse. Several service methods filter GCI rows back out
	 * ({@code validIsaStatedRelationship}, {@code isConceptModellingChanged}),
	 * so the distinction has to survive into the table rather than being
	 * flattened here.
	 */
	/**
	 * The identifier the incumbent gives an axiom-derived relationship.
	 *
	 * <p>These rows have no SCTID - they do not exist as RF2 relationships at
	 * all - so {@code SnomedDroolsComponentFactory} synthesises one from the
	 * axiom plus the relationship's position within it. Reproduced here because
	 * {@code RuleExecutor.execute} refuses to run at all if any relationship's
	 * id is null or empty, and because the id surfaces in {@code InvalidContent}
	 * as the component identifier - so a different scheme would make the two
	 * engines' violations look different when they are in fact the same.
	 *
	 * <p>Shape, taken from the 5.7.0 bytecode rather than assumed:
	 * <pre>{@code <axiomId>/Group_<g>/Type_<t>/Destination_<d>}</pre>
	 * with {@code /ConcreteValue_<v>} in place of the destination when the
	 * relationship carries a concrete value (destinationId -1).
	 */
	private static String compositeIdentifier(String axiomId, int group, Relationship r) {
		String tail = r.getDestinationId() == -1
				? "/ConcreteValue_" + (r.getValue() == null ? "" : r.getValue().asString())
				: "/Destination_" + r.getDestinationId();
		return axiomId + "/Group_" + group + "/Type_" + r.getTypeId() + tail;
	}

	/** Rows between Appender flushes. */
	private static final int BATCH_ROWS = 100_000;

	/** The Appender has no setNull; a null string is appended as a typed null. */
	private static void appendOrNull(DuckDBAppender ap, String value) throws SQLException {
		if (value == null) {
			ap.append((String) null);
		} else {
			ap.append(value);
		}
	}

	private void deriveStatedRelationships() throws SQLException {
		try (Statement s = connection.createStatement()) {
			// `id` is synthesised - see compositeIdentifier. An axiom-derived
			// relationship has no SCTID of its own, but RuleExecutor.execute
			// rejects the whole run if any relationship's id is null or empty,
			// so it cannot simply be left out.
			s.execute("CREATE TABLE stated_relationship ("
					+ "id VARCHAR, "
					+ "axiom_id VARCHAR, effective_time VARCHAR, active BOOLEAN, module_id VARCHAR, "
					+ "source_id VARCHAR, destination_id VARCHAR, type_id VARCHAR, "
					+ "relationship_group INTEGER, concrete_value VARCHAR, axiom_gci BOOLEAN)");
		}

		AxiomRelationshipConversionService converter =
				new AxiomRelationshipConversionService(Collections.emptySet());

		int rows = 0, axioms = 0, unconvertible = 0;
		// The Appender, not a batched INSERT. DuckDB's JDBC executeBatch is not
		// a bulk path: executeBatchedPreparedStatement loops the batch and calls
		// duckdb_jdbc_execute once PER ROW, so batching buys nothing. Deriving
		// this edition measured 22 s per 100,000 rows that way - about nine
		// minutes of pure insert - and as one unflushed batch it sat in a single
		// native call long enough that the JVM would not accept a jstack attach,
		// which is indistinguishable from a hang.
		//
		// Column ORDER is now positional and must match the CREATE TABLE above:
		// the Appender has no column names. That is the one thing to check if a
		// column is ever added.
		try (DuckDBAppender ap = ((DuckDBConnection) connection)
					 .createAppender(DuckDBConnection.DEFAULT_SCHEMA, "stated_relationship");
			 Statement q = connection.createStatement();
			 ResultSet rs = q.executeQuery(
					 "SELECT id, effectiveTime, active, moduleId, referencedComponentId, owlExpression "
					 + "FROM owl_refset")) {
			while (rs.next()) {
				String axiomId = rs.getString(1);
				String effectiveTime = rs.getString(2);
				boolean active = "1".equals(rs.getString(3));
				String moduleId = rs.getString(4);
				String owlExpression = rs.getString(6);
				axioms++;

				AxiomRepresentation axiom;
				try {
					axiom = converter.convertAxiomToRelationships(owlExpression);
				} catch (Exception e) {
					// Property-chain and similar axioms carry no relationship
					// representation. The incumbent records the axiom and moves
					// on; so do we.
					unconvertible++;
					continue;
				}
				if (axiom == null) {
					unconvertible++;
					continue;
				}

				Long named;
				Map<Integer, List<Relationship>> groups;
				boolean gci;
				if (axiom.getLeftHandSideNamedConcept() != null && axiom.getRightHandSideRelationships() != null) {
					named = axiom.getLeftHandSideNamedConcept();
					groups = axiom.getRightHandSideRelationships();
					gci = false;
				} else if (axiom.getRightHandSideNamedConcept() != null && axiom.getLeftHandSideRelationships() != null) {
					named = axiom.getRightHandSideNamedConcept();
					groups = axiom.getLeftHandSideRelationships();
					gci = true;
				} else {
					unconvertible++;
					continue;
				}

				for (Map.Entry<Integer, List<Relationship>> group : groups.entrySet()) {
					for (Relationship r : group.getValue()) {
						ap.beginRow();
						ap.append(compositeIdentifier(axiomId, group.getKey(), r));
						ap.append(axiomId);
						ap.append(effectiveTime);
						ap.append(active);
						ap.append(moduleId);
						ap.append(String.valueOf(named));
						appendOrNull(ap, r.getDestinationId() == -1
								? null : String.valueOf(r.getDestinationId()));
						ap.append(String.valueOf(r.getTypeId()));
						ap.append((int) group.getKey());
						appendOrNull(ap, r.getValue() == null ? null : r.getValue().asString());
						ap.append(gci);
						ap.endRow();
						rows++;
						if (rows % BATCH_ROWS == 0) {
							ap.flush();
							LOGGER.debug("Derived {} stated relationships from {} axioms so far...",
									rows, axioms);
						}
					}
				}
			}
			ap.flush();
		}
		try (Statement s = connection.createStatement()) {
			s.execute("CREATE INDEX idx_sr_source ON stated_relationship(source_id)");
			s.execute("CREATE INDEX idx_sr_dest ON stated_relationship(destination_id)");
		}
		LOGGER.info("Derived {} stated relationships from {} OWL axioms ({} carried no relationship form)",
				rows, axioms, unconvertible);
	}

	/**
	 * Transitive closure of active stated IS-A, as a table.
	 *
	 * <p>The incumbent computes ancestors per concept while loading and holds
	 * them in heap. A recursive CTE materialised once is both faster and the
	 * thing DuckDB is genuinely good at. GCI rows are excluded: an ancestor
	 * relation derived from a general concept inclusion is not a stated parent.
	 */
	private void buildStatedAncestorClosure() throws SQLException {
		try (Statement s = connection.createStatement()) {
			s.execute("CREATE TABLE stated_ancestor AS "
					+ "WITH RECURSIVE parent AS ("
					+ "  SELECT DISTINCT source_id, destination_id FROM stated_relationship "
					+ "  WHERE active AND NOT axiom_gci AND type_id = '" + Constants.IS_A + "' "
					+ "        AND destination_id IS NOT NULL"
					+ "), closure(concept_id, ancestor_id) AS ("
					+ "  SELECT source_id, destination_id FROM parent "
					+ "  UNION "
					+ "  SELECT c.concept_id, p.destination_id "
					+ "  FROM closure c JOIN parent p ON p.source_id = c.ancestor_id"
					+ ") SELECT concept_id, ancestor_id FROM closure");
			s.execute("CREATE INDEX idx_anc ON stated_ancestor(concept_id)");
		}
	}

	Set<String> queryStrings(String sql, Object... params) {
		Set<String> out = new HashSet<>();
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			bind(ps, params);
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) {
					String v = rs.getString(1);
					if (v != null) {
						out.add(v);
					}
				}
			}
		} catch (SQLException e) {
			throw new IllegalStateException("query failed: " + sql, e);
		}
		return out;
	}

	boolean queryExists(String sql, Object... params) {
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			bind(ps, params);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			throw new IllegalStateException("query failed: " + sql, e);
		}
	}

	private void bind(PreparedStatement ps, Object... params) throws SQLException {
		for (int i = 0; i < params.length; i++) {
			ps.setObject(i + 1, params[i]);
		}
	}

	@Override
	public void close() {
		try {
			connection.close();
		} catch (SQLException e) {
			LOGGER.warn("Failed to close DuckDB connection", e);
		}
	}
}
