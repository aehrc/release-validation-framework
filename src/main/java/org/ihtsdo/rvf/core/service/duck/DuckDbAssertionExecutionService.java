package org.ihtsdo.rvf.core.service.duck;

import org.ihtsdo.rvf.core.data.model.Assertion;
import org.ihtsdo.rvf.core.data.model.TestRunItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes RVF's SQL assertions on DuckDB.
 *
 * <p>The DuckDB counterpart of {@link
 * org.ihtsdo.rvf.core.service.AssertionExecutionService}, and deliberately much
 * smaller than it, because the work that class does per run has moved:
 *
 * <ul>
 * <li>No SQL transformation. The statements arrive already transpiled and split
 *     in the {@link DuckStore}; this class binds sentinels and executes.
 * <li>No CALL handling. DuckDB has no stored procedures and does not need them -
 *     the publisher unrolls every CALL into the plain SQL MySQL's procedure
 *     would have generated, because the {@code information_schema.tables} loops
 *     those procedures run are over a table set fixed by the DDL.
 * <li>No {@code ENGINE = MyISAM} appending, and no statement-shape sniffing for
 *     "select" or "create table". A columnar engine has one execution path.
 * </ul>
 *
 * <p>What it keeps is the contract: a {@link TestRunItem} per assertion, with the
 * same fields the MySQL path sets, so a report is comparable record for record.
 *
 * <p>Deliberately NOT a Spring {@code @Service}. It is constructed per run
 * against one DuckDB connection, because the schemas it queries are that
 * connection's - a singleton holding a connection to a database that is created
 * and dropped per validation would be the wrong lifetime.
 */
public class DuckDbAssertionExecutionService {

	private final Logger logger = LoggerFactory.getLogger(DuckDbAssertionExecutionService.class);

	private final DuckStore store;
	private final DuckBinder binder;
	private final Connection connection;

	public DuckDbAssertionExecutionService(DuckStore store, DuckBinder binder,
			Connection connection) {
		this.store = store;
		this.binder = binder;
		this.connection = connection;
	}

	/** What a preparation step did, so a run can log it rather than infer it. */
	public record SetupResult(int applied, List<String> failures) {
	}

	/**
	 * Applies the pre-requisites, then the ports.
	 *
	 * <p>Per statement, continuing past failures, because pre-requisites.sql
	 * mixes the {@code *_active} table builds (which must succeed) with MySQL
	 * routine definitions (which have no DuckDB equivalent and are replaced by
	 * the ports). Aborting the file on the first of those would leave every table
	 * unbuilt.
	 *
	 * <p>The ORDER is load-bearing and it is this way round: the ports build
	 * {@code transitiveClosureTable} from {@code relationship_active}, which is a
	 * pre-requisite table. Running ports first cost 12 of 45 setup statements -
	 * the closure failed on the missing relation and every statement after it
	 * failed on the connection's pending result. What made that dangerous is that
	 * the assertion results did not move at all: the macros lost are amtv4's, and
	 * the corpus under test does not call them. A silent 12-statement regression
	 * is exactly what an ordering comment is for.
	 */
	public SetupResult prepareSchema() {
		List<String> failures = new ArrayList<>();
		int applied = 0;
		List<String> setup = new ArrayList<>(store.prerequisiteStatements());
		setup.addAll(store.ports());
		for (String raw : setup) {
			DuckBinder.Bound bound = binder.bind(raw, "prereq");
			if (bound.isSkipped()) {
				continue;
			}
			try (Statement st = connection.createStatement()) {
				st.execute(bound.sql());
				applied++;
			} catch (SQLException e) {
				failures.add(abbreviate(raw) + " -> " + e.getMessage());
			}
		}
		logger.info("DuckDB schema prepared: {} statements applied, {} failed",
				applied, failures.size());
		return new SetupResult(applied, failures);
	}

	/**
	 * Runs every assertion the store holds for the given list, in order.
	 *
	 * <p>Single-threaded on purpose. The MySQL service batches ten assertions per
	 * thread because each one is a round trip to a server that parallelises
	 * poorly per connection; DuckDB parallelises WITHIN a query across all cores,
	 * so running assertions concurrently mostly makes them contend. It also makes
	 * per-assertion timings meaningless - measured against a serial run of the
	 * same corpus, concurrent wall-clock read a median of 7.1x high, which is how
	 * a 0.04s assertion once got reported as a 69.9s outlier.
	 */
	public List<TestRunItem> execute(List<Assertion> assertions) {
		Map<String, DuckStore.StoredAssertion> stored = store.assertions();
		List<TestRunItem> results = new ArrayList<>();
		for (Assertion assertion : assertions) {
			results.add(executeOne(assertion, stored.get(String.valueOf(assertion.getUuid()))));
		}
		return results;
	}

	private TestRunItem executeOne(Assertion assertion, DuckStore.StoredAssertion stored) {
		long start = System.currentTimeMillis();
		TestRunItem item = new TestRunItem();
		item.setTestCategory(assertion.getKeywords());
		item.setAssertionText(assertion.getAssertionText());
		item.setAssertionUuid(assertion.getUuid());
		item.setSeverity(assertion.getSeverity());

		if (stored == null) {
			// The store is built from the same corpus RVF loads assertions from,
			// so a miss means the two are out of step - a stale store, or an
			// assertion added to manifest.xml since it was published. Reported as
			// a failure message, which makes it testsIncomplete rather than a
			// silent pass.
			item.setFailureMessage("No precompiled statements in the DuckDB store for assertion "
					+ assertion.getUuid() + " - store and assertion corpus are out of step");
			item.setRunTime(System.currentTimeMillis() - start);
			return item;
		}

		// The assertion id, not the uuid: RVF binds <ASSERTIONUUID> to
		// String.valueOf(assertion.getAssertionId()), and several assertions
		// interpolate it into a numeric column unquoted. Using the uuid here
		// would break those and would not join back to qa_result.
		String assertionId = String.valueOf(assertion.getAssertionId());
		List<String> skippedFor = new ArrayList<>();
		for (String raw : stored.statements()) {
			DuckBinder.Bound bound = binder.bind(raw, assertionId);
			if (bound.isSkipped()) {
				skippedFor.add(bound.skippedFor());
				continue;
			}
			try (Statement st = connection.createStatement()) {
				st.execute(bound.sql());
			} catch (SQLException e) {
				item.setFailureMessage("Error executing DuckDB statement: " + e.getMessage());
				item.setRunTime(System.currentTimeMillis() - start);
				return item;
			}
		}
		if (!skippedFor.isEmpty() && countExecuted(stored, skippedFor) == 0) {
			// Every statement wanted a release this run does not hold, so the
			// assertion did not run at all. Saying so is the honest outcome: a
			// zero failure count here would report a pass for a check nothing
			// performed.
			item.setFailureMessage("Not run: requires " + String.join(", ", skippedFor.stream().distinct().toList())
					+ ", which was not supplied to this validation");
		}
		item.setRunTime(System.currentTimeMillis() - start);
		return item;
	}

	private int countExecuted(DuckStore.StoredAssertion stored, List<String> skipped) {
		return stored.statements().size() - skipped.size();
	}

	/**
	 * Failure counts per assertion id, in one pass over qa_result.
	 *
	 * <p>One grouped query rather than one per assertion: the corpus has 400-odd
	 * assertions and the N+1 shape was measurable next to the run itself.
	 */
	public Map<String, Long> failureCounts(long runId, String qaResultTable) throws SQLException {
		Map<String, Long> out = new LinkedHashMap<>();
		String sql = "select assertion_id, count(*) from " + qaResultTable
				+ " where run_id = " + runId + " group by assertion_id";
		try (Statement st = connection.createStatement();
				java.sql.ResultSet rs = st.executeQuery(sql)) {
			while (rs.next()) {
				out.put(rs.getString(1), rs.getLong(2));
			}
		}
		return out;
	}

	private static String abbreviate(String sql) {
		String flat = sql.replace('\n', ' ').replace('\t', ' ').trim();
		return flat.length() <= 70 ? flat : flat.substring(0, 70) + "...";
	}
}
