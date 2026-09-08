package org.ihtsdo.rvf.core.service.duck;

import org.ihtsdo.rvf.config.ExecutionEngine;
import org.ihtsdo.rvf.core.data.model.Assertion;
import org.ihtsdo.rvf.core.data.model.TestRunItem;
import org.ihtsdo.rvf.core.data.model.AssertionGroup;
import org.ihtsdo.rvf.core.data.model.AssertionTest;
import org.ihtsdo.rvf.core.data.model.Test;
import org.ihtsdo.rvf.core.service.AssertionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * {@link AssertionService} over the precompiled store and the corpus XML,
 * so the REST layer works in DuckDB mode without an assertion database.
 *
 * <p>This class exists because of one coupling. Every controller that submits a
 * validation - {@code TestUploadFileController}, {@code AutomatedTestController}
 * - injects {@code AssertionService}, whose only implementation was
 * {@code AssertionServiceImpl}: JPA, {@code @Transactional}, and therefore
 * {@code @ConditionalOnMysqlEngine}. That single dependency is what made the
 * whole submission path vanish in DuckDB mode, leaving an application that boots
 * and cannot be asked to validate anything.
 *
 * <h2>What is not supported, and why that is stated rather than faked</h2>
 *
 * <p>The corpus is FILES here, resolved at publish time. Three groups of methods
 * have no meaning against it:
 *
 * <ul>
 * <li><b>Mutation</b> - {@code create}, {@code save}, {@code delete},
 *     {@code addTest}, {@code addAssertionToGroup} and the rest. There is
 *     nothing to write to; an assertion enters this corpus by being committed to
 *     the assertions repository and republished. These throw.
 * <li><b>Lookup by numeric id</b> - {@code find(Long)},
 *     {@code getTestsByAssertionId}, {@code getGroupsForAssertion(Long)}. The
 *     store keys on UUID and never assigns a numeric id, because the ids in a
 *     MySQL instance are database sequence values with no meaning outside it.
 *     These return empty.
 *
 *     <p>A stable id COULD be synthesised from the UUID and would make these
 *     endpoints respond. That is exactly why it is not done: the number would
 *     not be the number the MySQL instance uses, so a client holding an id from
 *     one would silently address a different assertion in the other. An empty
 *     result is wrong in a way the caller can see.
 * <li><b>{@code getAssertionTests}</b> - the store holds an assertion's
 *     STATEMENTS, already transpiled and split; the AssertionTest/Test rows are
 *     a MySQL-side normalisation of the same thing and are not reconstructible
 *     from it.
 * </ul>
 *
 * <p>None of these is on the path a validation takes. Groups are addressed by
 * NAME when a run is submitted, which is the whole of what
 * {@code MysqlValidationService.getAssertions} and its DuckDB counterpart use.
 */
@Service
@ConditionalOnProperty(name = ExecutionEngine.PROPERTY, havingValue = ExecutionEngine.DUCKDB)
public class DuckAssertionService implements AssertionService {

	private static final Logger LOGGER = LoggerFactory.getLogger(DuckAssertionService.class);

	private final DuckStoreLocator storeLocator;
	private final String corpusRoot;

	/**
	 * The store is read on FIRST USE, not here.
	 *
	 * <p>Deliberate, and it mirrors {@link DuckDbValidationService}: Spring
	 * builds singletons eagerly, so a constructor that read the store would make
	 * an unreadable one - or one that disagrees with the corpus - a condition of
	 * the application STARTING rather than of a validation running. A deployment
	 * would then fail to boot with a message about assertions, when what it
	 * wants to say is "this run cannot proceed". The context test asserts the
	 * mode boots without a corpus for exactly that reason.
	 */
	/**
	 * {@code rvf.assertion.packs} - the packs a deployment has pinned.
	 *
	 * <p>Empty by default, which is every current deployment: the bundled store
	 * alone. A pack is only ever loaded because configuration named it AND
	 * pinned its digest.
	 */
	private final List<String> packSpecs;

	@Autowired
	public DuckAssertionService(DuckStoreLocator storeLocator,
			@Value("${rvf.assertion.resource.local.path:}") String corpusRoot,
			@Value("${rvf.assertion.packs:}") List<String> packSpecs) {
		this.storeLocator = storeLocator;
		this.corpusRoot = corpusRoot;
		this.packSpecs = packSpecs == null ? List.of() : packSpecs;
	}

	DuckAssertionService(DuckAssertionSource source) {
		this.storeLocator = null;
		this.corpusRoot = null;
		this.packSpecs = List.of();
		this.loaded = source;
	}

	private volatile DuckAssertionSource loaded;

	/**
	 * The store the current {@link #loaded} source was built from.
	 *
	 * <p>Kept because the source exposes assertions in RVF's model, which
	 * carries no SQL - the STATEMENTS live in the store. Answering "what does
	 * this assertion actually run" needs the store itself.
	 */
	private volatile DuckStore store;

	private DuckAssertionSource source() {
		DuckAssertionSource current = loaded;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			if (loaded == null) {
				try {
					DuckStore read = storeLocator.load();
					loaded = DuckAssertionSource.from(read, Path.of(corpusRoot));
					store = read;
				} catch (IOException e) {
					throw new UncheckedIOException("Failed to read the DuckDB assertion store "
							+ storeLocator.description(), e);
				}
				LOGGER.info("DuckDB assertion corpus: {} assertions in {} groups from {}",
						loaded.findAll().size(), loaded.populatedGroupNames().size(),
						storeLocator.description());
			}
			return loaded;
		}
	}

	/** The packs the current corpus was assembled from, newest swap wins. */
	private volatile List<DuckStorePacks.Pack> packs = List.of();

	/**
	 * Replaces the assertion corpus with the bundled store merged with these
	 * packs, atomically.
	 *
	 * <p>Atomic in the only sense that matters here: the new corpus is built
	 * and validated COMPLETELY before anything is published, and the swap
	 * itself is two volatile writes. A validation already running holds its own
	 * reference and finishes against the corpus it started with - which is the
	 * behaviour you want, because a run that changed assertion sets halfway
	 * would produce a report describing neither.
	 *
	 * <p>On any failure the current corpus keeps serving and the exception
	 * carries the reason. The alternative - a half-applied swap, or an empty
	 * corpus after a bad fetch - reports every release as clean.
	 *
	 * @return a description of what is now loaded
	 */
	public synchronized String reload(List<DuckStorePacks.Pack> incoming) throws IOException {
		// The bundled store is always the base. A pack set that replaced it
		// rather than extending it could quietly drop the international corpus,
		// and nothing in a report would look different.
		DuckStore base = storeLocator.load();
		List<DuckStorePacks.Pack> all = new java.util.ArrayList<>();
		all.add(new DuckStorePacks.Pack("bundled", storeLocator.description(),
				"bundled", base));
		all.addAll(incoming);

		DuckStore merged = DuckStorePacks.merge(all);
		// Built before the swap, so a corpus the source cannot read leaves the
		// old one in place instead of taking the engine down with it.
		DuckAssertionSource candidate = DuckAssertionSource.from(merged, Path.of(corpusRoot));
		verifyExecutable(merged, candidate);

		this.store = merged;
		this.loaded = candidate;
		this.packs = List.copyOf(incoming);

		String description = candidate.findAll().size() + " assertions in "
				+ candidate.populatedGroupNames().size() + " groups from "
				+ (incoming.isEmpty() ? "the bundled store only"
						: incoming.size() + " pack(s): " + incoming.stream()
								.map(DuckStorePacks.Pack::label).toList());
		LOGGER.info("DuckDB assertion corpus reloaded: {}", description);
		return description;
	}

	/**
	 * Does this corpus actually execute? Answered before the swap, not at 4am.
	 *
	 * <p>Digest verification proves a pack is what was approved and the merge
	 * proves two packs can be combined. Neither proves the combination RUNS. A
	 * pack's SQL can call a macro its base defines, and the base can stop
	 * defining it - which is not hypothetical: a publisher change dropped
	 * {@code substring_index} and four assertions died with "Scalar Function
	 * with name substring_index does not exist". Inside one store that surfaced
	 * in a test. Across packs fetched at runtime it would surface in a
	 * validation, hours later, as findings that never appeared.
	 *
	 * <p>So the merged store is applied to a throwaway in-memory DuckDB with
	 * EMPTY tables in the shapes it declares, and every assertion is executed
	 * against them. That catches what this check exists for - a missing macro,
	 * a missing table, a missing column, a statement that will not parse -
	 * because DuckDB binds all of those at execution.
	 *
	 * <p>What it deliberately does NOT catch is anything data-dependent. With
	 * no rows, {@code mapGroup = ''} against a SMALLINT never evaluates its
	 * cast and passes here, and that exact assertion errored in production for
	 * years. Empty tables answer "is this corpus coherent", and a real release
	 * answers "is it correct" - which is
	 * {@code AssertionCorpusDigestTest}'s job, against a committed fixture, at
	 * build time. Two questions, two checks; conflating them would make this
	 * one slow enough to skip.
	 *
	 * <p>Assertions needing a release this check does not supply are SKIPPED by
	 * the binder, not failed - the same path production takes when no previous
	 * release is configured.
	 */
	private void verifyExecutable(DuckStore merged, DuckAssertionSource candidate)
			throws IOException {
		long start = System.currentTimeMillis();
		try {
			Class.forName("org.duckdb.DuckDBDriver");
		} catch (ClassNotFoundException e) {
			throw new IOException("no DuckDB driver to verify the corpus with", e);
		}
		try (java.sql.Connection con = java.sql.DriverManager.getConnection("jdbc:duckdb:")) {
			try (java.sql.Statement st = con.createStatement()) {
				// All THREE release schemas, each with the declared table
				// shapes and no rows. Prospective alone is not enough: an
				// assertion may mix statements that read the previous release
				// with statements that do not, and with no previous schema the
				// binder skips the first kind and the rest then fail on a
				// temporary table the skipped statement would have built. That
				// is a fact about the RELEASE configuration, not about whether
				// the packs cohere, and this check is only asking the latter.
				//
				// (It is also a real production behaviour worth knowing: a run
				// with no previous release does not cleanly skip such an
				// assertion, it fails it. component-centric-snapshot-
				// description-active-inactive-term-match.sql is one.)
				for (String schema : List.of("prospective", "previous", "dependency")) {
					st.execute("CREATE SCHEMA IF NOT EXISTS " + schema);
					for (Map.Entry<String, String> table : merged.tableColumns().entrySet()) {
						st.execute("CREATE TABLE IF NOT EXISTS " + schema + "." + table.getKey()
								+ " (" + table.getValue() + ")");
					}
				}
				// Both settings the engine needs, and neither carries to a new
				// connection - see DuckDbValidationService.
				st.execute("SET search_path='prospective'");
				st.execute("SET old_implicit_casting=true");
				st.execute("CREATE SCHEMA IF NOT EXISTS rvf_results");
				st.execute("CREATE TABLE rvf_results.qa_result ("
						+ "id BIGINT, run_id BIGINT, assertion_id VARCHAR, concept_id BIGINT, "
						+ "details VARCHAR, component_id VARCHAR, table_name VARCHAR, "
						+ "skip_module_check BOOLEAN)");
			}
			DuckBinder binder = new DuckBinder(merged.sentinels(), new DuckBinder.Config(
					1L, "prospective", "previous", "dependency", "rvf_results.qa_result",
					null, List.of(), "20000101"));
			DuckDbAssertionExecutionService service =
					new DuckDbAssertionExecutionService(merged, binder, con);
			service.prepareSchema();

			// Resource assertions FIRST, exactly as DuckDbValidationService runs
			// them (selection.resources() before selection.assertions()). They
			// build the shared intermediate tables the rest select from -
			// res_edited_active_concepts, tmp_pt, ancestors - so in store order
			// four real assertions fail on a table nothing has created yet. A
			// check that does not reproduce production's ORDER measures a
			// corpus production never runs.
			List<Assertion> ordered = new java.util.ArrayList<>();
			List<Assertion> rest = new java.util.ArrayList<>();
			for (Assertion assertion : candidate.findAll()) {
				boolean isResource = assertion.getKeywords() != null
						&& java.util.Arrays.stream(assertion.getKeywords().split(","))
								.map(String::trim).anyMatch("resource"::equals);
				(isResource ? ordered : rest).add(assertion);
			}
			ordered.addAll(rest);

			List<String> broken = new java.util.ArrayList<>();
			for (TestRunItem item : service.execute(ordered)) {
				String message = item.getFailureMessage();
				if (message != null && !message.isBlank()
						&& !message.startsWith("Not run: requires")) {
					broken.add(merged.assertions()
							.get(String.valueOf(item.getAssertionUuid())).file()
							+ ": " + message);
				}
			}
			if (!broken.isEmpty()) {
				throw new IOException("the merged corpus does not execute - "
						+ broken.size() + " assertion(s) failed against empty tables, so the "
						+ "packs are internally inconsistent rather than merely disagreeing:\n  "
						+ String.join("\n  ", broken.subList(0, Math.min(10, broken.size()))));
			}
			LOGGER.info("Corpus verified executable: {} assertions in {}ms",
					candidate.findAll().size(), System.currentTimeMillis() - start);
		} catch (java.sql.SQLException e) {
			throw new IOException("could not verify the merged corpus: " + e.getMessage(), e);
		} catch (DuckDbAssertionExecutionService.SetupFailedException e) {
			// The prerequisites and ports themselves do not apply, so nothing
			// downstream of them would have been trustworthy either.
			throw new IOException("the merged corpus setup failed on "
					+ e.getFailures().size() + " statement(s): "
					+ String.join("; ", e.getFailures().subList(0,
							Math.min(5, e.getFailures().size()))), e);
		}
	}

	/**
	 * What the loaded corpus is made of, for the report and for an operator.
	 *
	 * <p>This is the provenance packs cost. Baked into the image, the tag named
	 * the corpus; fetched at runtime, only this can answer which assertions
	 * produced a report.
	 */
	public List<DuckStorePacks.Pack> loadedPacks() {
		source();
		return packs;
	}

	/**
	 * Fetches the configured packs and swaps, or leaves the corpus alone.
	 *
	 * <p>Configuration, not a request body: a caller who can name a URL can
	 * name any URL, and a pack is executable SQL. The endpoint decides WHEN to
	 * re-read what a deployment has already pinned - which is what makes an
	 * assertion update a config change plus a POST rather than an image build.
	 *
	 * <p>Each entry is {@code name=<name>;version=<v>;uri=<u>;sha256=<hex>},
	 * semicolon-separated because a URL contains commas far more often than it
	 * contains semicolons, and Spring splits list properties on commas.
	 */
	public String refreshConfiguredPacks() throws IOException {
		List<AssertionPackFetcher.Source> sources = configuredPackSources();
		if (sources.isEmpty()) {
			// Not an error: the bundled store alone is a legitimate deployment,
			// and it is what every current one runs.
			return reload(List.of());
		}
		return reload(new AssertionPackFetcher().fetch(sources));
	}

	List<AssertionPackFetcher.Source> configuredPackSources() {
		List<AssertionPackFetcher.Source> sources = new java.util.ArrayList<>();
		for (String spec : packSpecs) {
			if (spec == null || spec.isBlank()) {
				continue;
			}
			java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
			for (String part : spec.split(";")) {
				String[] kv = part.split("=", 2);
				if (kv.length == 2) {
					fields.put(kv[0].trim().toLowerCase(java.util.Locale.ROOT), kv[1].trim());
				}
			}
			sources.add(new AssertionPackFetcher.Source(
					fields.getOrDefault("name", ""),
					fields.getOrDefault("version", ""),
					java.net.URI.create(fields.getOrDefault("uri", "")),
					fields.getOrDefault("sha256", ""),
					// By reference, so the token itself is a secret the platform
					// supplies rather than a string in a values file.
					fields.get("authheader")));
		}
		return sources;
	}

	/**
	 * What an assertion actually executes - its file, keywords and transpiled
	 * statements.
	 *
	 * <p>Empty for an assertion this store does not carry, which includes every
	 * Drools rule and MRCM check: those are not SQL and have no statements to
	 * show. The caller has to say so rather than imply the assertion is unknown.
	 */
	public Optional<DuckStore.StoredAssertion> storedAssertion(String uuid) {
		source();
		DuckStore current = store;
		return current == null
				? Optional.empty()
				: Optional.ofNullable(current.assertions().get(uuid));
	}

	@Override
	public List<Assertion> findAll() {
		return source().findAll();
	}

	@Override
	public Assertion findAssertionByUUID(UUID uuid) {
		return source().getAssertionByUuid(uuid);
	}

	@Override
	public Assertion getAssertionByUuid(UUID assertionUUID) {
		return source().getAssertionByUuid(assertionUUID);
	}

	@Override
	public List<Assertion> getAssertionsByKeyWords(String keyWord, boolean exactMatch) {
		return source().getAssertionsByKeyWords(keyWord, exactMatch);
	}

	@Override
	public Long count() {
		return (long) source().findAll().size();
	}

	@Override
	public AssertionGroup getAssertionGroupByName(String groupName) {
		if (!source().populatedGroupNames().contains(groupName)) {
			return null;
		}
		return group(groupName);
	}

	@Override
	public List<AssertionGroup> getAssertionGroupsByNames(List<String> groupNames) {
		List<AssertionGroup> groups = new ArrayList<>();
		for (String name : groupNames) {
			AssertionGroup group = getAssertionGroupByName(name);
			if (group != null) {
				groups.add(group);
			}
		}
		return groups;
	}

	@Override
	public List<AssertionGroup> getAllAssertionGroups() {
		List<AssertionGroup> groups = new ArrayList<>();
		for (String name : source().populatedGroupNames()) {
			groups.add(group(name));
		}
		return groups;
	}

	@Override
	public List<AssertionGroup> getGroupsForAssertion(Assertion assertion) {
		List<AssertionGroup> groups = new ArrayList<>();
		if (assertion != null && assertion.getUuid() != null) {
			for (String name : source().groupsOf(assertion.getUuid())) {
				groups.add(group(name));
			}
		}
		return groups;
	}

	private AssertionGroup group(String name) {
		AssertionGroup group = new AssertionGroup();
		group.setName(name);
		group.setAssertions(new HashSet<>(source().getAssertionsInGroups(List.of(name))));
		return group;
	}

	// ---- keyed on a numeric id the store does not have: empty, not fabricated

	@Override
	public Assertion find(Long id) {
		LOGGER.debug("find({}) - the DuckDB corpus is keyed on UUID and has no numeric ids", id);
		return null;
	}

	@Override
	public List<Test> getTestsByAssertionId(Long assertionId) {
		return List.of();
	}

	@Override
	public List<AssertionGroup> getGroupsForAssertion(Long assertionId) {
		return List.of();
	}

	@Override
	public List<AssertionTest> getAssertionTests(Assertion assertion) {
		return List.of();
	}

	@Override
	public List<Test> getTests(Assertion assertion) {
		return List.of();
	}

	// ---- mutation: there is nothing to write to

	@Override
	public Assertion create(Assertion assertion) {
		throw readOnly("create an assertion");
	}

	@Override
	public Assertion save(Assertion assertion) {
		throw readOnly("save an assertion");
	}

	@Override
	public void delete(Assertion assertion) {
		throw readOnly("delete an assertion");
	}

	@Override
	public Assertion addTest(Assertion assertion, Test test) {
		throw readOnly("add a test");
	}

	@Override
	public Assertion addTest(Long assertionId, Test test) {
		throw readOnly("add a test");
	}

	@Override
	public Assertion addTests(Assertion assertion, Collection<Test> tests) {
		throw readOnly("add tests");
	}

	@Override
	public Assertion deleteTest(Assertion assertion, Test test) {
		throw readOnly("delete a test");
	}

	@Override
	public Assertion deleteTests(Assertion assertion, Collection<Test> tests) {
		throw readOnly("delete tests");
	}

	@Override
	public AssertionGroup addAssertionToGroup(Assertion assertion, AssertionGroup group) {
		throw readOnly("add an assertion to a group");
	}

	@Override
	public AssertionGroup removeAssertionFromGroup(Assertion assertion, AssertionGroup group) {
		throw readOnly("remove an assertion from a group");
	}

	@Override
	public AssertionGroup createAssertionGroup(AssertionGroup group) {
		throw readOnly("create an assertion group");
	}

	private static UnsupportedOperationException readOnly(String what) {
		return new UnsupportedOperationException("Cannot " + what + ": with "
				+ ExecutionEngine.PROPERTY + "=" + ExecutionEngine.DUCKDB + " the assertion corpus "
				+ "is the published store, not a database. Change the assertions repository and "
				+ "republish the store.");
	}
}
