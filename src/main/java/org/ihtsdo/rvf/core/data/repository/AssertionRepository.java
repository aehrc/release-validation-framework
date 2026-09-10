package org.ihtsdo.rvf.core.data.repository;

import org.ihtsdo.rvf.core.data.model.Assertion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
@Repository
public interface AssertionRepository extends JpaRepository<Assertion, Long> {

	Assertion findByUuid(String uuid);

	List<Assertion> findAssertionsByKeywords(String keyWords);
	
	Assertion findByAssertionId(Long assertionId);

	/**
	 * How many assertions the store holds.
	 *
	 * <p>Native, and overriding {@link JpaRepository#count()} deliberately.
	 * Spring Data implements the inherited one as the JPQL string
	 * {@code select count(o) from Assertion o}, and this application pins
	 * antlr4-runtime at 4.7.1 for presto-parser, which leaves Hibernate 6's HQL
	 * lexer unable to initialise - so the inherited method threw
	 * {@code ExceptionInInitializerError} rather than returning a number. See
	 * the comment on that dependency in pom.xml for the measurement, and why
	 * bumping the version trades this defect for a worse one.
	 */
	@Query(value = "select count(*) from assertion", nativeQuery = true)
	@Override
	long count();
}
