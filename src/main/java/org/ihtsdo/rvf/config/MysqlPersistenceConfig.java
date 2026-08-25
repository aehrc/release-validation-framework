package org.ihtsdo.rvf.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * RVF's JPA wiring, lifted off {@link Config} so it can be switched off.
 *
 * <p>{@code @EntityScan} and {@code @EnableJpaRepositories} are class-level
 * annotations, so there is no way to condition them where they were - on
 * {@link Config}, which the application class extends. Moving them to a
 * configuration class of their own is what makes a condition possible at all:
 * {@code @Conditional} is evaluated before the class's imports are processed,
 * so a non-matching condition means the repository registrar never runs and
 * Hibernate is never asked to build an EntityManagerFactory.
 *
 * <p>Bean-for-bean identical to the previous arrangement when the condition
 * holds - same packages, same registrar, same repositories.
 */
@Configuration
@ConditionalOnMysqlEngine
@EntityScan("org.ihtsdo.rvf.core.data.model")
@EnableJpaRepositories("org.ihtsdo.rvf.core.data.repository")
public class MysqlPersistenceConfig {
}
