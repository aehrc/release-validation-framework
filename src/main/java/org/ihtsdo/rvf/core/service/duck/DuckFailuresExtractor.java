package org.ihtsdo.rvf.core.service.duck;

import org.ihtsdo.otf.rest.client.RestClientException;
import org.ihtsdo.rvf.core.data.model.Assertion;
import org.ihtsdo.rvf.core.data.model.AssertionGroup;
import org.ihtsdo.rvf.core.data.model.FailureDetail;
import org.ihtsdo.rvf.core.data.model.TestRunItem;
import org.ihtsdo.rvf.core.service.WhitelistService;
import org.ihtsdo.rvf.core.service.config.MysqlExecutionConfig;
import org.ihtsdo.rvf.core.service.whitelist.WhitelistItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Reads back qa_result to fill in each {@link TestRunItem}'s failure count and
 * sample failures - the DuckDB counterpart of
 * {@link org.ihtsdo.rvf.core.service.MysqlFailuresExtractor}.
 *
 * <p>Three real differences from the MySQL version, everything else reproduced
 * exactly:
 *
 * <ul>
 * <li>No {@code BasicDataSource} / {@code dataSource.getDefaultCatalog()}. This
 *     is constructed per run against one already-open DuckDB {@link Connection}
 *     and the caller's fully-qualified qa_result table name (e.g.
 *     {@code "rvf_results.qa_result"}) - there is one DuckDB per run, not a
 *     shared server with per-run catalogs, so there is nothing to look up and
 *     nothing for this class to open or close.
 * <li>No {@code uuidToAssertionIdMap}. RVF binds {@code <ASSERTIONUUID>} to the
 *     assertion's numeric id today, but every one of the 646 occurrences in the
 *     assertion corpus is a quoted string literal, never a numeric context - so
 *     qa_result.assertion_id can hold the uuid text directly, and the mapping
 *     MysqlFailuresExtractor needs to translate between the two disappears. The
 *     uuid IS the key, throughout.
 * <li>DuckDB paging. MySQL's {@code limit offset,count} is not DuckDB syntax;
 *     this uses {@code limit ? offset ?}, which is count-then-offset - the
 *     OPPOSITE parameter order from the MySQL version's
 *     {@code setInt(3, offset); setInt(4, rowCount)}. Binding it in MySQL order
 *     would not fail, it would quietly fetch a differently-sized, differently
 *     placed slice of the same assertion's failures.
 * </ul>
 */
public class DuckFailuresExtractor {

	private final Logger logger = LoggerFactory.getLogger(DuckFailuresExtractor.class);

	private final Connection connection;
	private final String qaResultTable;
	private final WhitelistService whitelistService;
	private final Supplier<List<Assertion>> assertionsWithGroups;
	private final int whitelistBatchSize;

	public DuckFailuresExtractor(Connection connection, String qaResultTable,
			WhitelistService whitelistService, Supplier<List<Assertion>> assertionsWithGroups) {
		// 1000 mirrors MysqlFailuresExtractor's @Value("${rvf.assertion.whitelist.batchsize:1000}")
		// default; this class has no Spring context to read that property from.
		this(connection, qaResultTable, whitelistService, assertionsWithGroups, 1000);
	}

	/**
	 * @param assertionsWithGroups assertions carrying their group names. A
	 *        supplier rather than an AssertionService because that bean is
	 *        MySQL-backed and does not exist when rvf.execution.engine=duckdb -
	 *        taking it would have made this class boot-time dependent on the
	 *        database the whole path exists to stop needing.
	 *        DuckAssertionSource::findAll satisfies it from the store.
	 */
	public DuckFailuresExtractor(Connection connection, String qaResultTable,
			WhitelistService whitelistService, Supplier<List<Assertion>> assertionsWithGroups, int whitelistBatchSize) {
		this.connection = connection;
		this.qaResultTable = requireIdentifier(qaResultTable, "qaResultTable");
		this.whitelistService = whitelistService;
		this.assertionsWithGroups = assertionsWithGroups;
		this.whitelistBatchSize = whitelistBatchSize;
	}

	/**
	 * A schema-qualified SQL identifier and nothing else.
	 *
	 * <p>Deliberately strict rather than a quoting routine: every identifier this
	 * class interpolates is one WE choose (a table from the store, a configured
	 * qa_result name), so anything outside this shape is a bug or an attack, and
	 * in neither case is the right answer to escape it and carry on.
	 */
	private static final java.util.regex.Pattern IDENTIFIER =
			java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*");

	private static String requireIdentifier(String value, String what) {
		if (value == null || !IDENTIFIER.matcher(value).matches()) {
			throw new IllegalArgumentException(what + " is not a plain SQL identifier: " + value);
		}
		return value;
	}

	/**
	 * Whether {@code table} is a table this store declares.
	 *
	 * <p>Checked against the catalog rather than a name pattern: a name-shaped
	 * string that happens to be a real relation elsewhere would still pass a
	 * pattern check, and the catalog is the actual authority on what this
	 * connection can read.
	 */
	private boolean isKnownTable(String table) {
		if (table == null || !IDENTIFIER.matcher(table).matches()) {
			return false;
		}
		int dot = table.lastIndexOf('.');
		String schema = dot < 0 ? null : table.substring(0, dot);
		String name = dot < 0 ? table : table.substring(dot + 1);
		String sql = "select 1 from information_schema.tables where lower(table_name) = lower(?)"
				+ (schema == null ? "" : " and lower(table_schema) = lower(?)");
		try (PreparedStatement ps = connection.prepareStatement(sql)) {
			ps.setString(1, name);
			if (schema != null) {
				ps.setString(2, schema);
			}
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next();
			}
		} catch (SQLException e) {
			logger.warn("Could not verify table {} against the catalog", table, e);
			return false;
		}
	}

	/**
	 * Fills in {@code failureCount} and {@code firstNInstances} on every item.
	 *
	 * <p>Takes no {@code List<Assertion>}: MysqlFailuresExtractor's only used its
	 * copy to build the now-deleted uuid-to-id map. {@code items} already carry
	 * their own uuid, which is the key this class joins on.
	 */
	public void extractTestResults(List<TestRunItem> items, MysqlExecutionConfig config) throws SQLException, RestClientException {
		Map<String, Long> assertionIdToTotalFailureMap = getAssertionIdToTotalFailureMap(config);
		if (whitelistService.isWhitelistDisabled()) {
			extractTestResults(items, assertionIdToTotalFailureMap, config);
		} else {
			validateFailuresAndExtractTestResults(items, config, assertionIdToTotalFailureMap);
		}
	}

	private void extractTestResults(List<TestRunItem> items, Map<String, Long> assertionIdToTotalFailureMap, MysqlExecutionConfig config) throws SQLException {
		for (TestRunItem item : items) {
			String key = item.getAssertionUuid().toString();
			if (assertionIdToTotalFailureMap.containsKey(key)) {
				item.setFailureCount(assertionIdToTotalFailureMap.get(key));
				item.setFirstNInstances(fetchFailureDetails(config.getExecutionId(), key, config.getFailureExportMax(), null, null));
			} else if (!StringUtils.hasLength(item.getFailureMessage())) {
				// Ran, found nothing.
				item.setFailureCount(0L);
				item.setFirstNInstances(null);
			} else {
				// Did not run at all (testsIncomplete) - -1 says so rather than
				// reading as a clean pass.
				item.setFailureCount(-1L);
				item.setFirstNInstances(null);
			}
		}
	}

	private void validateFailuresAndExtractTestResults(List<TestRunItem> items, MysqlExecutionConfig config, Map<String, Long> assertionIdToTotalFailureMap) throws SQLException, RestClientException {
		List<Assertion> assertions = getAssertionsAndJoinGroups();
		Map<UUID, Assertion> uuidAssertionMap = assertions.stream().collect(Collectors.toMap(Assertion::getUuid, Function.identity()));

		for (TestRunItem item : items) {
			UUID assertionUuid = item.getAssertionUuid();
			String key = assertionUuid.toString();
			if (StringUtils.hasLength(item.getFailureMessage())) {
				item.setFailureCount(-1L);
				item.setFirstNInstances(null);
			} else if (assertionIdToTotalFailureMap.containsKey(key)) {
				int batchCounter = 0;
				int totalWhitelistedFailures = 0;
				int totalFilteredOutFailures = 0;
				long totalFailures = assertionIdToTotalFailureMap.get(key);
				boolean belongToCommonAuthoringOrCommonEditionGroup = uuidAssertionMap.containsKey(assertionUuid) && uuidAssertionMap.get(assertionUuid).getGroups() != null
						&& (uuidAssertionMap.get(assertionUuid).getGroups().contains("common-edition") || uuidAssertionMap.get(assertionUuid).getGroups().contains("common-authoring"));
				List<FailureDetail> firstNInstances = new ArrayList<>();
				while (batchCounter * whitelistBatchSize < totalFailures && firstNInstances.size() < config.getFailureExportMax()) {
					int offset = batchCounter * whitelistBatchSize;
					List<FailureDetail> failureDetails = fetchFailureDetails(config.getExecutionId(), key, -1, offset, whitelistBatchSize);
					failureDetails.forEach(this::setModuleAndFullFields);

					// filter by the extension modules only
					if (belongToCommonAuthoringOrCommonEditionGroup && config.isExtensionValidation() && !CollectionUtils.isEmpty(config.getIncludedModules())) {
						int totalBatchFailures = failureDetails.size();
						failureDetails = failureDetails.stream().filter(failure -> Boolean.TRUE.equals(failure.getSkipModuleCheck()) || config.getIncludedModules().contains(failure.getModuleId())).toList();
						totalFilteredOutFailures += (totalBatchFailures - failureDetails.size());
					}

					if (!failureDetails.isEmpty()) {
						// Convert to WhitelistItem
						List<WhitelistItem> whitelistItems = failureDetails.stream()
								.map(failureDetail -> new WhitelistItem(key, StringUtils.hasLength(failureDetail.getComponentId()) ? failureDetail.getComponentId() : "", failureDetail.getConceptId(), failureDetail.getFullComponent()))
								.toList();

						// Send to Authoring acceptance gateway
						List<WhitelistItem> whitelistedItems = whitelistService.checkComponentFailuresAgainstWhitelist(whitelistItems);

						// Find the failures which are not in the whitelisted item
						List<FailureDetail> validFailures = failureDetails.stream().filter(failure ->
								whitelistedItems.stream().noneMatch(whitelistedItem -> failure.getComponentId().equals(whitelistedItem.getComponentId()))
						).toList();

						totalWhitelistedFailures += whitelistedItems.size();
						firstNInstances.addAll(validFailures);
					}
					batchCounter++;
				}

				if (firstNInstances.isEmpty()) {
					item.setFailureCount(0L);
					item.setFirstNInstances(null);
				} else {
					item.setFailureCount(totalFailures - totalWhitelistedFailures - totalFilteredOutFailures);
					item.setFirstNInstances(firstNInstances.size() > config.getFailureExportMax() ? firstNInstances.subList(0, config.getFailureExportMax()) : firstNInstances);
				}
			} else {
				item.setFailureCount(0L);
				item.setFirstNInstances(null);
			}
		}
	}

	/**
	 * Column lookups here are BY POSITION - i==1 id, i==2 effectiveTime, i==4
	 * moduleId - not by name. That mirrors MysqlFailuresExtractor exactly, and it
	 * is safe here for the same reason it is safe there: {@link DuckMaterialiser}
	 * builds every table's columns in the same order as RVF's own
	 * create-tables-mysql.sql, which is what the MySQL loader also builds from.
	 * If a table's column order ever diverges from that DDL, this silently
	 * attaches the wrong value as moduleId instead of failing - there is no
	 * schema check to catch it, so it is worth stating plainly rather than
	 * discovering it from a wrong report.
	 */
	private void setModuleAndFullFields(final FailureDetail failureDetail) {
		if (!StringUtils.hasLength(failureDetail.getComponentId())) {
			return;
		}
		// Cross-catalog in MySQL, but here it is just another schema on the same
		// DuckDB connection - table_name already carries its schema prefix (e.g.
		// "prospective.concept_s"), so no connection switch is needed.
		//
		// The table name is CONCATENATED, because an identifier cannot be a bind
		// parameter. So it is validated first. Today every table_name in the
		// corpus is a quoted literal naming an RF2 table, which means the value
		// is corpus-controlled and not reachable from the untrusted input (the
		// submitted release package) - but "not reachable today" is a property of
		// the assertion corpus, not of this code, and the corpus is edited by
		// hand. MysqlFailuresExtractor:146 concatenates it unchecked; that is
		// inherited, not a reason to keep it.
		String table = failureDetail.getTableName();
		if (!isKnownTable(table)) {
			// Enrichment only, so skipping costs a moduleId and a fullComponent
			// on one finding - never a missed finding. Logged because a
			// legitimate table falling out of the known set would otherwise show
			// up as a silently unfiltered result much later.
			logger.warn("Not enriching failure detail: table_name {} is not a known "
					+ "table in this store", table);
			return;
		}
		String sql = "select * from " + table + " where id = ?";
		try (PreparedStatement preparedStatement = connection.prepareStatement(sql)) {
			preparedStatement.setString(1, failureDetail.getComponentId());
			try (ResultSet resultSet = preparedStatement.executeQuery()) {
				if (resultSet.next()) {
					int columnCount = resultSet.getMetaData().getColumnCount();
					StringBuilder additionalFields = new StringBuilder();
					for (int i = 1; i <= columnCount; i++) {
						// Ignore columns: id and effective time
						if (i == 1 || i == 2) {
							continue;
						}
						// Column moduleId
						if (i == 4) {
							failureDetail.setModuleId(resultSet.getString(i));
						}
						if (!additionalFields.isEmpty()) {
							additionalFields.append(",");
						}
						additionalFields.append(resultSet.getString(i));
					}
					failureDetail.setFullComponent(additionalFields.toString());
				}
			}
		} catch (SQLException exception) {
			logger.error("Error retrieving additional fields for component id {} against table {}", failureDetail.getComponentId(), failureDetail.getTableName());
		}
	}

	private Map<String, Long> getAssertionIdToTotalFailureMap(MysqlExecutionConfig config) throws SQLException {
		Map<String, Long> assertionIdToTotalFailureMap = new HashMap<>();
		String totalSQL = "select assertion_id, count(*) total from " + qaResultTable + " where run_id = ? group by assertion_id";
		try (PreparedStatement preparedStatement = connection.prepareStatement(totalSQL)) {
			preparedStatement.setLong(1, config.getExecutionId());
			try (ResultSet resultSet = preparedStatement.executeQuery()) {
				while (resultSet.next()) {
					assertionIdToTotalFailureMap.put(resultSet.getString(1), resultSet.getLong(2));
				}
			}
		}
		return assertionIdToTotalFailureMap;
	}

	private List<FailureDetail> fetchFailureDetails(Long executionId, String assertionUuid, int failureExportMax, Integer offset, Integer rowCount)
			throws SQLException {
		String resultSQL = "select concept_id, details, component_id, table_name, skip_module_check from " + qaResultTable +
				" where assertion_id = ? and run_id = ?";
		if (offset != null && rowCount != null) {
			// DuckDB has no "limit offset,count" form. "limit ? offset ?" is
			// count-then-offset, the reverse of the MySQL parameter order below -
			// see the setInt calls.
			resultSQL += " limit ? offset ?";
		} else if (failureExportMax > 0) {
			resultSQL += " limit ?";
		}
		List<FailureDetail> firstNInstances = new ArrayList<>();
		long counter = 0;
		try (PreparedStatement preparedStatement = connection.prepareStatement(resultSQL)) {
			// select results that match execution
			preparedStatement.setString(1, assertionUuid);
			preparedStatement.setLong(2, executionId);
			if (offset != null && rowCount != null) {
				// Reversed from MysqlFailuresExtractor's setInt(3, offset); setInt(4,
				// rowCount): DuckDB's "limit ? offset ?" takes the row COUNT first and
				// the OFFSET second. Swapping these two lines back to the MySQL order
				// would not error - it would silently page through the wrong slice.
				preparedStatement.setInt(3, rowCount);
				preparedStatement.setInt(4, offset);
			} else if (failureExportMax > 0) {
				preparedStatement.setLong(3, failureExportMax);
			}
			try (ResultSet resultSet = preparedStatement.executeQuery()) {
				while (resultSet.next()) {
					// only get first N failed results
					if (failureExportMax < 0 || counter < failureExportMax) {
						FailureDetail detail = new FailureDetail(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getString(4), resultSet.getBoolean(5));
						firstNInstances.add(detail);
					}
					counter++;
				}
			}
		}
		return firstNInstances;
	}

	private List<Assertion> getAssertionsAndJoinGroups() {
		// MysqlFailuresExtractor joins assertions to groups here with a triple
		// nested forEach over every assertion and every group. The store-backed
		// source has already done it - group membership is resolved once, at
		// construction, by the same AssertionGroupImporter rule engine.
		return assertionsWithGroups.get();
	}

}
