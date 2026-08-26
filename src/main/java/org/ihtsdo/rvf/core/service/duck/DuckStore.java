package org.ihtsdo.rvf.core.service.duck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The precompiled assertion store: RVF's SQL corpus already transpiled to DuckDB.
 *
 * <p>Transpilation is a pure function of the assertion TEXT - nothing in it
 * consults a run id, an assertion uuid or a schema name - so it happens once, at
 * publish time, and the result is this artefact. What reaches a run is a list of
 * DuckDB statements per assertion with placeholder SENTINELS where the run's
 * values go; {@link DuckBinder} substitutes them.
 *
 * <p>Doing it this way is what keeps the MySQL-to-DuckDB dialect work (sqlglot,
 * plus five AST passes) out of the Java service entirely. The service never
 * parses SQL. It also makes the transpiled corpus a reviewable, diffable,
 * version-stamped artefact that a build can gate on, rather than something
 * regenerated invisibly on every run - which matters because the transpiler's
 * parse-failure path falls back to the untranspiled MySQL, and a per-run
 * transpile has nowhere to report that.
 *
 * <p>Read-only and immutable: load once, share across a run.
 */
public final class DuckStore {

	/** Bumped by the publisher when the store's shape changes incompatibly. */
	public static final int SUPPORTED_FORMAT_VERSION = 1;

	private final JsonNode root;

	private DuckStore(JsonNode root) {
		this.root = root;
	}

	public static DuckStore read(Path storeFile) throws IOException {
		return parse(Files.readString(storeFile));
	}

	public static DuckStore parse(String json) throws IOException {
		JsonNode root = new ObjectMapper().readTree(json);
		int version = root.path("formatVersion").asInt(-1);
		if (version != SUPPORTED_FORMAT_VERSION) {
			// Refuse rather than read a store shaped for a different runtime.
			// Every field below is looked up by name with a silent default, so a
			// renamed section would otherwise read as "empty" and the run would
			// report zero findings and pass.
			throw new IOException("store formatVersion " + version
					+ " is not supported (expected " + SUPPORTED_FORMAT_VERSION + ")");
		}
		return new DuckStore(root);
	}

	/** One assertion's precompiled statements and the metadata to report it. */
	public record StoredAssertion(String uuid, String file, String text,
			String keywords, String severity, List<String> statements) {
	}

	/**
	 * {@code placeholder -> sentinel}, in the order the publisher applied them.
	 *
	 * <p>Order is preserved deliberately: every identifier sentinel ends in "_"
	 * as a terminator precisely because {@code <MODULEID>}'s sentinel would
	 * otherwise be a prefix of {@code <MODULEIDS>}'s, and binding in a different
	 * order from the one that installed them can corrupt overlapping tokens.
	 */
	public Map<String, String> sentinels() {
		Map<String, String> out = new LinkedHashMap<>();
		for (JsonNode n : root.path("sentinels")) {
			out.put(n.path("placeholder").asText(), n.path("sentinel").asText());
		}
		return Collections.unmodifiableMap(out);
	}

	/** Assertions by uuid, in store order. */
	public Map<String, StoredAssertion> assertions() {
		Map<String, StoredAssertion> out = new LinkedHashMap<>();
		JsonNode node = root.path("assertions");
		node.fieldNames().forEachRemaining(uuid -> {
			JsonNode a = node.path(uuid);
			out.put(uuid, new StoredAssertion(uuid, a.path("file").asText(),
					a.path("text").asText(""), a.path("keywords").asText(""),
					a.path("severity").asText(""), strings(a.path("statements"))));
		});
		return Collections.unmodifiableMap(out);
	}

	/**
	 * Column definitions for every table the DDL declares, {@code table -> "col
	 * TYPE, col TYPE"}.
	 *
	 * <p>Used to create an EMPTY placeholder for a table the release does not
	 * ship. Without them an assertion that merely mentions an absent table dies
	 * with "Table with name ccsRefset_f does not exist" instead of correctly
	 * reporting no findings - and most such assertions are "no bad rows in X",
	 * which passes precisely because there are no rows to be bad.
	 */
	public Map<String, String> tableColumns() {
		Map<String, String> out = new LinkedHashMap<>();
		JsonNode node = root.path("tableColumns");
		node.fieldNames().forEachRemaining(t -> out.put(t, node.path(t).asText()));
		return Collections.unmodifiableMap(out);
	}

	/**
	 * DuckDB statements standing in for the MySQL routines the engine cannot
	 * create: the pre-requisite CREATE FUNCTION bodies (as MACROs), the
	 * transitive closure table, and cleanExpression.
	 *
	 * <p>Without these the {@code *_active} relations do not exist and every
	 * amtv4 assertion fails with "Table with name description_active does not
	 * exist" - which reads like a materialisation problem and is not.
	 */
	public List<String> ports() {
		return strings(root.path("ports"));
	}

	/** Pre-requisite statements, in file then statement order. */
	public List<String> prerequisiteStatements() {
		List<String> out = new ArrayList<>();
		for (JsonNode p : root.path("prerequisites")) {
			out.addAll(strings(p.path("statements")));
		}
		return Collections.unmodifiableList(out);
	}

	/**
	 * {@code assertion file name -> sha256 prefix of the MySQL it was compiled
	 * from}, as recorded by the publisher.
	 *
	 * <p>This is what makes a store that ships INSIDE the artefact safe. The
	 * store is a build output of one specific assertion corpus; move the corpus
	 * pin without republishing and the two disagree silently, because nothing
	 * downstream reads the corpus SQL any more - the run would execute the OLD
	 * assertions and report them under the NEW corpus's identity. Comparing
	 * these hashes against the corpus on disk turns that into a loud failure.
	 *
	 * <p>Assertions only. Pre-requisites are published from a separate input
	 * that the corpus does not ship, so there is nothing on the corpus side to
	 * compare them against.
	 */
	public Map<String, String> assertionSourceHashes() {
		Map<String, String> out = new LinkedHashMap<>();
		JsonNode node = root.path("assertions");
		node.fieldNames().forEachRemaining(uuid -> {
			JsonNode a = node.path(uuid);
			String file = a.path("file").asText();
			String sha = a.path("sha256").asText("");
			if (!file.isEmpty() && !sha.isEmpty()) {
				out.put(file, sha);
			}
		});
		return Collections.unmodifiableMap(out);
	}

	/** Provenance: what built this store, from what corpus, when. */
	public String generatorDescription() {
		JsonNode g = root.path("generator");
		return g.isMissingNode() ? "unknown" : g.toString();
	}

	private static List<String> strings(JsonNode array) {
		List<String> out = new ArrayList<>();
		for (JsonNode n : array) {
			out.add(n.asText());
		}
		return Collections.unmodifiableList(out);
	}
}
