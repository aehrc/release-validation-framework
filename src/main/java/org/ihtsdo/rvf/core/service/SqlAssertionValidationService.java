package org.ihtsdo.rvf.core.service;

import org.ihtsdo.rvf.core.service.config.ValidationRunConfig;
import org.ihtsdo.rvf.core.service.pojo.ValidationStatusReport;

/**
 * The SQL-assertion pass of a validation, whichever engine performs it.
 *
 * <p>{@code ValidationRunner} orchestrates five passes - structural, SQL
 * assertions, Drools, MRCM and traceability. Four of them already work off RF2
 * files and know nothing about a database. The fifth was bound to
 * {@code MysqlValidationService} by name, on one line, and that single reference
 * is what made the whole orchestrator {@code @ConditionalOnMysqlEngine} and
 * therefore absent in DuckDB mode - so a submitted validation was enqueued and
 * nothing drained the queue.
 *
 * <p>The interface is deliberately this narrow. It is not "a validation
 * service": it is the one pass whose implementation depends on where the
 * release data lives. Anything wider would pull the engine-neutral passes into
 * an abstraction they do not need.
 *
 * <p>Implementations acquire their own inputs from {@code validationConfig},
 * because the two engines want different things from the same package - MySQL
 * loads the ZIP into a schema, DuckDB reads an unpacked directory - and a
 * signature that handed over one shape would force the other to undo it.
 */
public interface SqlAssertionValidationService {

	/**
	 * Runs the SQL assertions and returns {@code statusReport}, filled in.
	 *
	 * <p>Declares {@code throws Exception} because the MySQL implementation
	 * throws and the DuckDB one does not, and forcing them to agree here would
	 * change one of them. {@code ValidationRunner} submits this as a
	 * {@code Callable}, so a thrown exception surfaces from {@code Future.get()}
	 * exactly as it does today; the DuckDB implementation reports failures on
	 * the status report instead, which is the better contract but not one to
	 * impose on the incumbent as a side effect of extracting an interface.
	 *
	 * @param statusReport carries an empty {@code ValidationReport} the
	 *                     implementation fills in rather than replaces - that is
	 *                     what {@code ValidationRunner} constructs and later
	 *                     merges
	 */
	ValidationStatusReport runRF2Validations(ValidationRunConfig validationConfig,
			ValidationStatusReport statusReport) throws Exception;
}
