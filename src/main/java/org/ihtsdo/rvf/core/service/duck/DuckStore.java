package org.ihtsdo.rvf.core.service.duck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HexFormat;

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

	/**
	 * This store's OWN identity, empty for one published before it had any.
	 *
	 * <p>Distinct from {@link #packs()}: that is what a merged store was
	 * assembled from, this is what a single store IS. The bundled store used to
	 * report as {@code bundled} with no version and no digest, so nothing could
	 * be required of it and a report could not name the assertions that produced
	 * it.
	 *
	 * <p>The digest is CHECKED here rather than copied. A digest a runtime only
	 * repeats is a claim; one it recomputes is a fact, and the cost is 360
	 * hashes of a uuid and a hash on a path that already parsed a 600KB tree.
	 */
	public Optional<PackRecord> identity() throws IOException {
		JsonNode pack = root.path("pack");
		if (pack.isMissingNode() || pack.isNull()) {
			return Optional.empty();
		}
		String declared = pack.path("digest").asText("");
		String actual = assertionDigest();
		if (!declared.equals(actual)) {
			throw new IOException("store " + pack.path("name").asText("?") + "@"
					+ pack.path("version").asText("?") + " declares digest "
					+ declared + " but its assertions hash to " + actual
					+ ". The store has been edited since it was published, so its "
					+ "identity does not describe what it would run.");
		}
		return Optional.of(new PackRecord(
				pack.path("name").asText(),
				pack.path("version").asText(),
				declared,
				assertions().size()));
	}

	/**
	 * {@code sha256} over everything this store would EXECUTE.
	 *
	 * <p>The publisher's definition, reproduced exactly: the sorted
	 * {@code uuid \t source hash} pairs, then {@code --}, then the sorted
	 * {@code prerequisite file \t hash} pairs.
	 *
	 * <p>Over identities rather than the serialised store, so key order,
	 * indentation and a later added field cannot change it. Over the
	 * PREREQUISITES too, because they build the tables every assertion reads: a
	 * store with a changed pre-requisites.sql runs differently while its
	 * assertion set is untouched, and a digest blind to that cannot answer
	 * "would this reproduce the old report".
	 */
	public String assertionDigest() {
		StringBuilder material = new StringBuilder();
		JsonNode assertions = root.path("assertions");
		List<String> uuids = new ArrayList<>();
		assertions.fieldNames().forEachRemaining(uuids::add);
		Collections.sort(uuids);
		for (int i = 0; i < uuids.size(); i++) {
			if (i > 0) {
				material.append('\n');
			}
			material.append(uuids.get(i)).append('\t')
					.append(assertions.path(uuids.get(i)).path("sha256").asText(""));
		}
		List<String> prerequisites = new ArrayList<>();
		for (JsonNode node : root.path("prerequisites")) {
			prerequisites.add(node.path("file").asText() + '\t'
					+ node.path("sha256").asText(""));
		}
		Collections.sort(prerequisites);
		material.append("\n--\n").append(String.join("\n", prerequisites));
		try {
			MessageDigest sha = MessageDigest.getInstance("SHA-256");
			return "sha256:" + HexFormat.of()
					.formatHex(sha.digest(material.toString().getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("no SHA-256", e);
		}
	}

	/**
	 * The store as JSON, for merging packs.
	 *
	 * <p>Returns the parsed tree's own serialisation rather than the string it
	 * was read from: a merged store has no source string, and handing back the
	 * original would make {@code merge(merge(a, b), c)} silently lose b.
	 */
	public String toJson() {
		return root.toString();
	}

	public int formatVersion() {
		return root.path("formatVersion").asInt(-1);
	}

	/** The literal a run id is substituted for. */
	public String runIdSentinel() {
		return root.path("runIdSentinel").asText("");
	}

	/** The table name {@code qa_result} is rewritten to per run. */
	public String qaResultToken() {
		return root.path("qaResultToken").asText("");
	}

	/** Every table the DDL declares, whether or not a release ships it. */
	public List<String> knownTables() {
		return strings(root.path("knownTables"));
	}

	/**
	 * The transpiler that compiled these statements, e.g. {@code sqlglot 30.15.0}.
	 *
	 * <p>Load-bearing when packs are merged: two sqlglot versions in one store
	 * means two dialects, and nothing downstream could say which assertion was
	 * compiled by which.
	 */
	public String transpilerVersion() {
		JsonNode generator = root.path("generator");
		return generator.path("sqlglot").asText(
				generator.path("tool").asText(""));
	}

	/** One pack recorded in a merged store: its identity and its size. */
	public record PackRecord(String name, String version, String digest, int assertions) {
	}

	/**
	 * The packs this store was assembled from, empty for an unmerged one.
	 *
	 * <p>Provenance travels INSIDE the artefact rather than beside it, so a
	 * report can say which assertions produced it by reading the store it
	 * executed. A service holding a second copy of the answer is a second
	 * answer, and the two can disagree exactly when it matters - after a
	 * reload.
	 */
	public List<PackRecord> packs() {
		List<PackRecord> out = new ArrayList<>();
		for (JsonNode node : root.path("packs")) {
			out.add(new PackRecord(
					node.path("name").asText(),
					node.path("version").asText(),
					node.path("digest").asText(),
					node.path("assertions").asInt()));
		}
		return Collections.unmodifiableList(out);
	}

	/**
	 * One thing this pack requires of another pack in the same merge.
	 *
	 * <p>{@code atLeast} is a version; {@code digest} is exact. Both are needed:
	 * a pack whose SQL calls a macro the base only defines from some date needs
	 * a floor, and a pack validated against one exact corpus needs equality.
	 */
	public record Requirement(String pack, Kind kind, String value) {

		public enum Kind { AT_LEAST, DIGEST }

		/** How this requirement should read in a refusal. */
		public String describe() {
			return pack + (kind == Kind.AT_LEAST ? " at least " : " at digest ") + value;
		}
	}

	/**
	 * What this pack requires of the rest of the merge, empty when it says
	 * nothing.
	 *
	 * <p>The failure this exists for: when the publisher regression dropped
	 * {@code substring_index}, four assertions died at RUN time with "Scalar
	 * Function with name substring_index does not exist" - a pack fetched
	 * cleanly, verified against its digest, merged without conflict, and broke
	 * at 4am. A pack that can state what it needs of its base turns that into a
	 * refusal at merge, naming both versions.
	 *
	 * <p>An UNKNOWN requirement key is refused rather than ignored. An ignored
	 * requirement is worse than no requirement: it reads as a checked
	 * combination and is an unchecked one.
	 */
	public List<Requirement> requirements() throws IOException {
		List<Requirement> out = new ArrayList<>();
		for (JsonNode node : root.path("requires")) {
			String pack = node.path("pack").asText("");
			if (pack.isBlank()) {
				throw new IOException("a requires entry names no pack: " + node);
			}
			// The unknown key FIRST: an entry saying `atMost` has neither
			// atLeast nor digest either, and "exactly one of atLeast or digest"
			// describes a consequence while `atMost` is the author's actual
			// mistake - and the one that would otherwise be silently dropped.
			List<String> unknown = new ArrayList<>();
			node.fieldNames().forEachRemaining(field -> {
				if (!List.of("pack", "atLeast", "digest", "reason").contains(field)) {
					unknown.add(field);
				}
			});
			if (!unknown.isEmpty()) {
				throw new IOException("the requires entry for " + pack + " uses "
						+ unknown + ", which this runtime does not understand. Ignoring "
						+ "it would make an unchecked pack combination read as a checked "
						+ "one, so the store is refused instead.");
			}
			boolean atLeast = node.hasNonNull("atLeast");
			boolean digest = node.hasNonNull("digest");
			if (atLeast == digest) {
				throw new IOException("the requires entry for " + pack + " must have "
						+ "exactly one of atLeast or digest, and has "
						+ (atLeast ? "both" : "neither") + ": " + node);
			}
			out.add(new Requirement(pack,
					atLeast ? Requirement.Kind.AT_LEAST : Requirement.Kind.DIGEST,
					atLeast ? node.path("atLeast").asText() : node.path("digest").asText()));
		}
		return Collections.unmodifiableList(out);
	}

	/**
	 * What produced this store, whether or not anything was merged.
	 *
	 * <p>{@link #packs()} is empty for an unmerged store, which is every
	 * deployment that pins no packs - so a report and {@code GET
	 * /assertions/packs} both said nothing at all about the assertions that ran.
	 * An unmerged store answers with its own {@link #identity()}, which is the
	 * same question asked of a store of one.
	 *
	 * <p>One owner of the answer, and it is the artefact: a service caching this
	 * beside the store is a second answer, and the two disagree exactly after a
	 * reload, when it matters.
	 */
	public List<PackRecord> provenance() throws IOException {
		List<PackRecord> merged = packs();
		if (!merged.isEmpty()) {
			return merged;
		}
		return identity().map(List::of).orElseGet(List::of);
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
