package org.ihtsdo.rvf.core.service.duck;

import org.ihtsdo.rvf.core.data.model.Assertion;
import org.ihtsdo.rvf.importer.AssertionGroupImporter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The assertion corpus and its group membership, with no database anywhere.
 *
 * <p>The DuckDB counterpart of {@link
 * org.ihtsdo.rvf.core.service.AssertionService}, restricted to the two questions
 * a validation run actually asks it: which assertions are in these groups, and
 * which are the resource assertions. Everything else on that interface -
 * create, save, delete, addTest, the AssertionTest and Test graph - exists to
 * populate and edit MySQL rows, and there are no rows here.
 *
 * <p>Both inputs are already published artefacts, so this class composes rather
 * than parses:
 * <ul>
 * <li>{@link DuckStore} carries each assertion's uuid, text, keywords and
 *     severity alongside its precompiled statements. It is built from the same
 *     manifest the MySQL importer reads, so the metadata is the same metadata.
 * <li>{@link AssertionGroupImporter#resolveGroups} applies the {@code groups.xml}
 *     and {@code policies.xml} rules without touching a database. Those rules
 *     are subtle (exclusions beat includes, three include paths, special
 *     handling for the default categories) and reimplementing them here would be
 *     a second copy to keep in step with the corpus.
 * </ul>
 *
 * <p>Keyed on assertion UUID throughout. The numeric {@code assertionId} is a
 * MySQL AUTO_INCREMENT artefact - it is assigned by the insert order of a
 * startup import that does not happen in this mode, so there is no value it
 * could truthfully hold. It is deliberately left null rather than synthesised:
 * a fabricated id would look like an identity, join to nothing, and differ
 * between two runs of the same corpus.
 *
 * <p>Immutable and cheap to hold: resolve once per store, share across runs.
 */
public final class DuckAssertionSource {

	private final List<Assertion> assertions;
	private final Map<String, Assertion> byUuid;
	private final Map<String, Set<String>> groupsByUuid;

	private DuckAssertionSource(List<Assertion> assertions, Map<String, Set<String>> groupsByUuid) {
		this.assertions = List.copyOf(assertions);
		this.groupsByUuid = Map.copyOf(groupsByUuid);
		Map<String, Assertion> index = new LinkedHashMap<>();
		assertions.forEach(a -> index.put(a.getUuid().toString(), a));
		this.byUuid = Map.copyOf(index);
	}

	/**
	 * Reads the corpus from a store and the two grouping files.
	 *
	 * <p>{@code groups.xml} and {@code policies.xml} ship in the assertion corpus
	 * root, beside the {@code manifest.xml} the store was published from. Passing
	 * streams rather than paths keeps this usable from a resource manager, a jar
	 * entry or a test fixture.
	 */
	public static DuckAssertionSource from(DuckStore store, InputStream groupsXml,
			InputStream policiesXml) {
		List<Assertion> assertions = new ArrayList<>();
		for (DuckStore.StoredAssertion stored : store.assertions().values()) {
			assertions.add(toAssertion(stored));
		}
		// Null AssertionService: resolveGroups is documented DB-free and never
		// touches the field. Constructing it here rather than injecting a bean is
		// what keeps the importer a MySQL-only bean while its rules stay
		// available in a mode that has no MySQL.
		Map<String, Set<String>> groups = new AssertionGroupImporter(null)
				.resolveGroups(groupsXml, policiesXml, assertions);
		// The @Transient groups field is what MysqlFailuresExtractor reads to
		// decide whether a failure is whitelist-eligible, so it is populated
		// here for the same reason the MySQL path populates it from the DB.
		assertions.forEach(a -> a.setGroups(groups.getOrDefault(a.getUuid().toString(), Set.of())));
		return new DuckAssertionSource(assertions, groups);
	}

	/** Convenience for the on-disk layout: a store file and a corpus root. */
	public static DuckAssertionSource from(Path storeFile, Path corpusRoot) throws IOException {
		DuckStore store = DuckStore.read(storeFile);
		try (InputStream groups = Files.newInputStream(corpusRoot.resolve("groups.xml"));
				InputStream policies = Files.newInputStream(corpusRoot.resolve("policies.xml"))) {
			return from(store, groups, policies);
		}
	}

	/**
	 * Every assertion in these groups, de-duplicated, in store order.
	 *
	 * <p>The query {@code MysqlValidationService} makes: it asks for the groups by
	 * name and unions their assertions into a Set. The union is done here instead,
	 * because an {@code AssertionGroup} is a JPA entity whose only purpose in that
	 * code is to be immediately flattened.
	 *
	 * <p>Store order, not hash order, so that two runs of the same corpus execute
	 * the same assertions in the same sequence and their reports diff cleanly.
	 * An unknown group name selects nothing and is not an error - which matches
	 * {@code getAssertionGroupsByNames}, and is worth knowing when a run reports
	 * zero assertions.
	 */
	public List<Assertion> getAssertionsInGroups(Collection<String> groupNames) {
		Set<String> wanted = new LinkedHashSet<>(groupNames);
		List<Assertion> out = new ArrayList<>();
		for (Assertion assertion : assertions) {
			Set<String> in = groupsByUuid.getOrDefault(assertion.getUuid().toString(), Set.of());
			if (in.stream().anyMatch(wanted::contains)) {
				out.add(assertion);
			}
		}
		return out;
	}

	/**
	 * Mirrors {@code AssertionService.getAssertionsByKeyWords}, including its
	 * asymmetry.
	 *
	 * <p>{@code exactMatch} compares the WHOLE keywords field, not one token of
	 * it - the MySQL implementation is a derived query on the column, so
	 * {@code ("resource", true)} finds assertions keyworded exactly "resource"
	 * and not those keyworded "resource,AU". That is the call
	 * {@code MysqlValidationService} makes before every run, so the difference is
	 * the difference between building the shared tables and not.
	 */
	public List<Assertion> getAssertionsByKeyWords(String keyWord, boolean exactMatch) {
		List<Assertion> out = new ArrayList<>();
		for (Assertion assertion : assertions) {
			String keywords = assertion.getKeywords();
			if (exactMatch ? keywords.equals(keyWord) : keywords.contains(keyWord)) {
				out.add(assertion);
			}
		}
		return out;
	}

	/** Every assertion the store holds, in store order. */
	public List<Assertion> findAll() {
		return assertions;
	}

	/** The assertion with this uuid, or null - as {@code findAssertionByUUID} does. */
	public Assertion getAssertionByUuid(UUID uuid) {
		return byUuid.get(uuid.toString());
	}

	/** The group names this assertion belongs to; empty for one in no group. */
	public Set<String> groupsOf(UUID uuid) {
		return groupsByUuid.getOrDefault(uuid.toString(), Set.of());
	}

	/**
	 * Every group name that selected at least one assertion, sorted.
	 *
	 * <p>Diagnostic: a requested group missing from this set is a run that will
	 * execute nothing, and saying which names ARE available turns that from a
	 * silent pass into an answerable question.
	 */
	public Set<String> populatedGroupNames() {
		Set<String> names = new TreeSet<>();
		groupsByUuid.values().forEach(names::addAll);
		return names;
	}

	private static Assertion toAssertion(DuckStore.StoredAssertion stored) {
		Assertion assertion = new Assertion();
		assertion.setUuid(UUID.fromString(stored.uuid()));
		assertion.setAssertionText(stored.text());
		assertion.setKeywords(stored.keywords());
		assertion.setSeverity(stored.severity());
		return assertion;
	}
}
