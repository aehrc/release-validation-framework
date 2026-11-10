package org.ihtsdo.rvf.core.service.duck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * How the engine configures every DuckDB connection that executes a store's
 * SQL - a run, the release-cache build, and the check that a merged pack set
 * executes.
 *
 * <p>Assertion statements, including those of assertion packs, execute on these
 * connections. {@link #lockDown} restricts them to the files that connection
 * needs, so a statement cannot read or write anything else on the server,
 * fetch over the network or install an extension.
 */
final class DuckConnections {

	private static final Logger LOGGER = LoggerFactory.getLogger(DuckConnections.class);

	private DuckConnections() {
	}

	/**
	 * The CPUs and memory DuckDB may use.
	 *
	 * <p>DuckDB sizes its thread pool from the machine, and it does NOT observe
	 * the restrictions a container or a scheduler imposes. Measured on this
	 * codebase: under {@code taskset -c 0-1}, {@code nproc} reports 2 and the
	 * JVM's {@code availableProcessors()} reports 2, while DuckDB still reports
	 * {@code threads=10} - the host's core count. A Kubernetes pod with
	 * {@code limits.cpu: 2} on a 64-core node gets a CFS quota, not two cores,
	 * so DuckDB would start 64 worker threads inside a two-core budget and run
	 * slower than with two. {@code availableProcessors()} observes both
	 * affinity and cgroup quotas.
	 *
	 * @param threads     {@code rvf.duck.threads}; 0 or less uses the CPUs available to the JVM
	 * @param memoryLimit {@code rvf.duck.memory.limit}, with a unit; blank keeps DuckDB's default
	 */
	record Limits(int threads, String memoryLimit) {

		Limits {
			memoryLimit = requireUnit(memoryLimit);
		}

		void apply(Connection connection) throws SQLException {
			int effectiveThreads = threads > 0 ? threads : Runtime.getRuntime().availableProcessors();
			try (Statement statement = connection.createStatement()) {
				statement.execute("SET threads = " + effectiveThreads);
				if (!memoryLimit.isEmpty()) {
					statement.execute("SET memory_limit = '" + memoryLimit + "'");
				}
			}
			LOGGER.info("DuckDB resource limits: threads={}{}", effectiveThreads,
					memoryLimit.isEmpty() ? "" : ", memory_limit=" + memoryLimit);
		}

		/**
		 * A memory limit without a unit fails the run, not the startup, so reject
		 * it here.
		 *
		 * <p>{@code rvf.duck.memory.limit} is passed to DuckDB verbatim as
		 * {@code SET memory_limit}, and DuckDB requires a unit. Given {@code 4} it
		 * raises "Unknown unit for memory" - but only when the connection is
		 * opened, which is after acquisition and materialisation. On a real
		 * edition that is twenty-five minutes before the operator learns the flag
		 * was wrong, and the whole SQL phase is lost while Drools and MRCM carry
		 * on, so the run reports partial results rather than a configuration
		 * error.
		 */
		private static String requireUnit(String memoryLimit) {
			if (memoryLimit == null || memoryLimit.isBlank()) {
				return "";
			}
			String trimmed = memoryLimit.trim();
			if (!trimmed.matches("(?i)\\d+(\\.\\d+)?\\s*(k|m|g|t)(i?b)?")) {
				throw new IllegalArgumentException(
						"rvf.duck.memory.limit must carry a unit, e.g. 4GB or 4GiB - got '"
						+ memoryLimit + "'. DuckDB accepts KB/MB/GB/TB (1000^i) and "
						+ "KiB/MiB/GiB/TiB (1024^i), and rejects a bare number only "
						+ "once the connection is opened, long after the release has "
						+ "been materialised.");
			}
			return trimmed;
		}
	}

	/**
	 * Confines a connection to {@code allowedDirectories} and freezes its
	 * configuration.
	 *
	 * <p>File access ({@code read_csv}, {@code read_text}, {@code COPY},
	 * {@code ATTACH}) is refused outside those directories, as are network reads
	 * and installing or auto-loading an extension. The configuration is then
	 * locked, so a statement cannot undo any of it. The search path can still
	 * be set, which is how a run moves between schemas; every other setting must
	 * be applied before this.
	 */
	static void lockDown(Connection connection, Collection<Path> allowedDirectories) throws SQLException {
		String allowed = allowedDirectories.stream()
				.map(dir -> "'" + dir.toAbsolutePath().normalize().toString().replace("'", "''") + "'")
				.collect(Collectors.joining(", "));
		try (Statement statement = connection.createStatement()) {
			statement.execute("SET allowed_directories = [" + allowed + "]");
			statement.execute("SET enable_external_access = false");
			statement.execute("SET autoinstall_known_extensions = false");
			statement.execute("SET autoload_known_extensions = false");
			statement.execute("SET lock_configuration = true");
		}
	}
}
