package org.ihtsdo.rvf.core.service.duck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Merges precompiled assertion packs into the one store an engine executes.
 *
 * <p>A pack is a published artefact, not a folder of scripts: the server cannot
 * compile SQL - transpilation is sqlglot at publish time, which is why a store
 * exists at all - so a pack carries statements already transpiled, plus the
 * source hashes those statements were compiled from, so
 * {@link DuckStoreLocator}'s corpus check still means something.
 *
 * <p>See {@code duck/ASSERTION-PACKS.md} for why this shape rather than a
 * layered image.
 *
 * <h2>The merge rules, and why each is what it is</h2>
 *
 * Taken from comparing the real 360-assertion international store against the
 * 560-assertion build that adds AMT, not from first principles:
 *
 * <ul>
 * <li><b>{@code formatVersion}, {@code runIdSentinel}, {@code qaResultToken},
 *     {@code sentinels}: must be identical.</b> These are the contract between
 *     the store and {@link DuckBinder}. A pack with a different sentinel table
 *     would have its placeholders left unbound, and unbound placeholders reach
 *     execution as literal text.
 * <li><b>The transpiler version must be identical.</b> Two sqlglot versions in
 *     one store means two dialects of DuckDB SQL, and nothing downstream could
 *     say which assertion was compiled by which.
 * <li><b>{@code knownTables} unions; {@code tableColumns} unions but overlaps
 *     must agree.</b> Both derive from RVF's own DDL, so an extension pack
 *     restates them identically - and if it does not, one pack's SQL is reading
 *     a table shape the other does not have.
 * <li><b>{@code assertions} are additive, and a uuid may repeat only if the
 *     entry is identical.</b> Identical repetition is normal: a national pack
 *     built from a combined corpus contains the international assertions too.
 *     Two DIFFERENT assertions under one uuid is a collision, and the report
 *     would attribute one's findings to the other.
 * <li><b>{@code ports} union BY MACRO NAME, and a redefinition is refused.</b>
 *     This is the rule that is not obvious. Ports are DuckDB macros emitted per
 *     PUBLISH rather than per corpus - the international store has 20 and the
 *     AMT build 19, and AMT carries its own {@code get_cr_ADRS_PT} - so
 *     appending them would let one pack silently change the meaning of another
 *     pack's SQL. That is the failure class that produces a plausible wrong
 *     report rather than an error.
 * <li><b>{@code prerequisites} union by file, and the same file with a
 *     different hash is refused</b>, for the same reason.
 * </ul>
 *
 * <p>Every refusal names the packs and the key, because a merge conflict is
 * something a person has to resolve in a publishing pipeline they may not own.
 */
public final class DuckStorePacks {

	/**
	 * A pack and its provenance.
	 *
	 * <p>{@code name}, {@code version} and {@code digest} are not decoration:
	 * once packs are fetched at runtime, "which assertions produced this
	 * report" is unanswerable without them, and trading a rebuild for silent
	 * drift is no trade. {@link #merge} carries them into the merged store so a
	 * run can record what it executed.
	 */
	public record Pack(String name, String version, String digest, DuckStore store) {

		/** How this pack should read in a conflict message. */
		public String label() {
			return name + (version == null || version.isBlank() ? "" : "@" + version);
		}
	}

	/** The merge was refused. Every conflict, not the first. */
	public static class ConflictException extends RuntimeException {

		private final List<String> conflicts;

		ConflictException(List<String> conflicts) {
			super("assertion packs cannot be merged:\n  " + String.join("\n  ", conflicts));
			this.conflicts = List.copyOf(conflicts);
		}

		public List<String> getConflicts() {
			return conflicts;
		}
	}

	/**
	 * The name a port defines: {@code CREATE OR REPLACE MACRO foo(...)},
	 * {@code ... TABLE x.dual ...}, {@code ... FUNCTION bar ...}.
	 *
	 * <p>Keyed on the name and not the whole statement, because two packs
	 * defining the same macro with different bodies is exactly the case worth
	 * refusing, and keying on the text would call that two ports.
	 */
	private static final Pattern PORT_NAME = Pattern.compile(
			"CREATE\\s+(?:OR\\s+REPLACE\\s+)?(MACRO|TABLE|FUNCTION|PROCEDURE)\\s+"
					+ "([\\w.\"`]+)", Pattern.CASE_INSENSITIVE);

	private DuckStorePacks() {
	}

	/**
	 * Merges packs in order, the first being the base.
	 *
	 * @throws ConflictException if any rule above is broken
	 */
	public static DuckStore merge(List<Pack> packs) throws IOException {
		if (packs.isEmpty()) {
			throw new IllegalArgumentException(
					"no packs to merge - an empty store reports every release as clean");
		}
		ObjectMapper mapper = new ObjectMapper();
		Pack base = packs.get(0);
		ObjectNode out = (ObjectNode) mapper.readTree(base.store().toJson());
		List<String> conflicts = new ArrayList<>();

		// Provenance first, so even a refused merge has told the caller what it
		// was trying to combine.
		ArrayNode provenance = out.putArray("packs");
		for (Pack pack : packs) {
			ObjectNode entry = provenance.addObject();
			entry.put("name", pack.name());
			entry.put("version", pack.version());
			entry.put("digest", pack.digest());
			entry.put("assertions", pack.store().assertions().size());
		}

		Map<String, String> ports = portsByName(base, conflicts);
		Map<String, JsonNode> prerequisites = prerequisitesByFile(base);
		Set<String> knownTables = new LinkedHashSet<>(base.store().knownTables());
		Map<String, String> tableColumns = new LinkedHashMap<>(base.store().tableColumns());
		ObjectNode assertions = (ObjectNode) out.path("assertions");

		for (Pack pack : packs.subList(1, packs.size())) {
			DuckStore store = pack.store();
			mustMatch(conflicts, base, pack, "formatVersion",
					base.store().formatVersion(), store.formatVersion());
			mustMatch(conflicts, base, pack, "runIdSentinel",
					base.store().runIdSentinel(), store.runIdSentinel());
			mustMatch(conflicts, base, pack, "qaResultToken",
					base.store().qaResultToken(), store.qaResultToken());
			mustMatch(conflicts, base, pack, "sentinels",
					base.store().sentinels(), store.sentinels());
			mustMatch(conflicts, base, pack, "transpiler version",
					base.store().transpilerVersion(), store.transpilerVersion());

			knownTables.addAll(store.knownTables());
			store.tableColumns().forEach((table, columns) -> {
				String existing = tableColumns.putIfAbsent(table, columns);
				if (existing != null && !existing.equals(columns)) {
					conflicts.add("table " + table + " is declared differently by "
							+ base.label() + " and " + pack.label()
							+ " - one pack's SQL reads a shape the other does not have");
				}
			});

			portsByName(pack, conflicts).forEach((name, statement) -> {
				String existing = ports.putIfAbsent(name, statement);
				if (existing != null && !existing.equals(statement)) {
					conflicts.add(pack.label() + " redefines " + name + ", already defined by "
							+ base.label() + " - a redefinition silently changes the meaning "
							+ "of the other pack's SQL");
				}
			});

			prerequisitesByFile(pack).forEach((file, node) -> {
				JsonNode existing = prerequisites.putIfAbsent(file, node);
				if (existing != null && !hashOf(existing).equals(hashOf(node))) {
					conflicts.add("prerequisite " + file + " differs between " + base.label()
							+ " and " + pack.label());
				}
			});

			JsonNode incoming = ((ObjectNode) toObject(store)).path("assertions");
			incoming.fieldNames().forEachRemaining(uuid -> {
				JsonNode entry = incoming.path(uuid);
				JsonNode existing = assertions.get(uuid);
				if (existing == null) {
					assertions.set(uuid, entry);
				} else if (!existing.equals(entry)) {
					// Identical repetition is fine and normal - a national pack
					// built from a combined corpus contains the international
					// assertions too. Two DIFFERENT assertions under one uuid
					// would have the report attribute one's findings to the
					// other.
					conflicts.add("assertion " + uuid + " is different in " + base.label()
							+ " (" + existing.path("file").asText() + ") and " + pack.label()
							+ " (" + entry.path("file").asText() + ")");
				}
			});
		}

		if (!conflicts.isEmpty()) {
			throw new ConflictException(conflicts);
		}

		ArrayNode tables = out.putArray("knownTables");
		knownTables.forEach(tables::add);
		ObjectNode columns = out.putObject("tableColumns");
		tableColumns.forEach(columns::put);
		ArrayNode portArray = out.putArray("ports");
		ports.values().forEach(portArray::add);
		ArrayNode prereqArray = out.putArray("prerequisites");
		prerequisites.values().forEach(prereqArray::add);

		return DuckStore.parse(out.toString());
	}

	private static JsonNode toObject(DuckStore store) throws IOException {
		return new ObjectMapper().readTree(store.toJson());
	}

	private static Map<String, String> portsByName(Pack pack, List<String> conflicts) {
		Map<String, String> byName = new LinkedHashMap<>();
		for (String statement : pack.store().ports()) {
			Matcher m = PORT_NAME.matcher(statement);
			// An unrecognised port is keyed on its own text: better to carry it
			// twice than to drop it, and better to say so than to guess a name.
			String key = m.find() ? m.group(2).toLowerCase(java.util.Locale.ROOT)
					: "verbatim:" + statement;
			String previous = byName.put(key, statement);
			if (previous != null && !previous.equals(statement)) {
				conflicts.add(pack.label() + " defines " + key + " twice, differently");
			}
		}
		return byName;
	}

	private static Map<String, JsonNode> prerequisitesByFile(Pack pack) throws IOException {
		Map<String, JsonNode> byFile = new LinkedHashMap<>();
		JsonNode array = toObject(pack.store()).path("prerequisites");
		for (JsonNode node : array) {
			byFile.put(node.path("file").asText(), node);
		}
		return byFile;
	}

	private static String hashOf(JsonNode prerequisite) {
		return prerequisite.path("sha256").asText("");
	}

	private static void mustMatch(List<String> conflicts, Pack base, Pack pack, String what,
			Object expected, Object actual) {
		if (!java.util.Objects.equals(expected, actual)) {
			conflicts.add(what + " differs: " + base.label() + " has " + expected
					+ ", " + pack.label() + " has " + actual);
		}
	}
}
