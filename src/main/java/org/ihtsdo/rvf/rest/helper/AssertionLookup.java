package org.ihtsdo.rvf.rest.helper;

import org.ihtsdo.rvf.core.data.model.Assertion;
import org.ihtsdo.rvf.core.service.AssertionService;
import org.ihtsdo.rvf.rest.exception.InvalidFormatException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves the {@code {id}} path variable the assertion REST endpoints accept,
 * which is either a UUID or a numeric database id.
 *
 * <p>Extracted because the resolution rules are load-bearing and are needed by
 * two controllers that must not disagree: the read endpoints, which are
 * engine-agnostic, and the administration endpoints, which are MySQL-only. A
 * private copy in each would be two places for the UUID-versus-id heuristic to
 * drift.
 *
 * <p>Deliberately NOT {@code @ConditionalOnMysqlEngine}: it only delegates to
 * {@link AssertionService}, which has an implementation in both engines.
 */
@Component
public class AssertionLookup {

	private final AssertionService assertionService;

	@Autowired
	public AssertionLookup(AssertionService assertionService) {
		this.assertionService = assertionService;
	}

	/**
	 * Attempts to look up id first as a UUID and if not, a database integer id
	 * value.
	 *
	 * <p>The hyphen test is the original discriminator and is kept exactly:
	 * changing it would change which of the two lookups a malformed id reaches,
	 * and therefore which error a client sees.
	 *
	 * <p>In DuckDB mode the numeric branch always yields {@code null}, because
	 * the published store is keyed on UUID and never assigns the sequence values
	 * a MySQL instance would. That is a documented property of
	 * {@code DuckAssertionService}, not an accident here.
	 *
	 * @param id assertion UUID or numeric assertion id
	 * @return the referenced assertion, or {@code null} if there is none
	 */
	public Assertion find(String id) {
		if ((id == null) || id.isEmpty()) {
			throw new InvalidFormatException("Id can't be null or empty");
		}
		if (id.contains("-")) {
			try {
				UUID uuid = UUID.fromString(id);
				return assertionService.findAssertionByUUID(uuid);
			} catch (IllegalArgumentException e) {
				throw new InvalidFormatException("Id is not a valid uuid:" + id);
			}
		} else {
			try {
				return assertionService.find(Long.valueOf(id));
			} catch (IllegalArgumentException e) {
				throw new InvalidFormatException("Id is not a valid assertion id:" + id);
			}
		}
	}
}
