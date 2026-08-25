package org.ihtsdo.rvf.core.service.duck;

import org.ihtsdo.rvf.config.ExecutionEngine;
import org.ihtsdo.rvf.core.data.model.Assertion;
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

	private final String storeFile;
	private final String corpusRoot;

	/**
	 * The store is read on FIRST USE, not here.
	 *
	 * <p>Deliberate, and it mirrors {@link DuckDbValidationService}: Spring
	 * builds singletons eagerly, so a constructor that insisted on a configured
	 * store would make {@code rvf.duck.store} a condition of the application
	 * STARTING rather than of a validation running. A deployment would then fail
	 * to boot with a message about assertions, when what it wants to say is
	 * "this run cannot proceed". The context test asserts the mode boots without
	 * one for exactly that reason.
	 */
	@Autowired
	public DuckAssertionService(@Value("${rvf.duck.store:}") String storeFile,
			@Value("${rvf.assertion.resource.local.path:}") String corpusRoot) {
		this.storeFile = storeFile;
		this.corpusRoot = corpusRoot;
	}

	DuckAssertionService(DuckAssertionSource source) {
		this.storeFile = null;
		this.corpusRoot = null;
		this.loaded = source;
	}

	private volatile DuckAssertionSource loaded;

	private DuckAssertionSource source() {
		DuckAssertionSource current = loaded;
		if (current != null) {
			return current;
		}
		synchronized (this) {
			if (loaded == null) {
				if (storeFile == null || storeFile.isBlank()) {
					throw new IllegalStateException("rvf.duck.store is not set. With "
							+ ExecutionEngine.PROPERTY + "=" + ExecutionEngine.DUCKDB
							+ " the assertion corpus comes from the published store, and there is "
							+ "no assertion database to fall back to.");
				}
				try {
					loaded = DuckAssertionSource.from(Path.of(storeFile), Path.of(corpusRoot));
				} catch (IOException e) {
					throw new UncheckedIOException(
							"Failed to read the DuckDB assertion store " + storeFile, e);
				}
				LOGGER.info("DuckDB assertion corpus: {} assertions in {} groups from {}",
						loaded.findAll().size(), loaded.populatedGroupNames().size(), storeFile);
			}
			return loaded;
		}
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
