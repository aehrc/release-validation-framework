package org.ihtsdo.rvf.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Registers the annotated bean only when this process runs the MySQL engine.
 *
 * <p>{@code matchIfMissing = true} is the whole point: every bean carrying this
 * annotation existed unconditionally before, and an absent or {@code mysql}
 * {@value ExecutionEngine#PROPERTY} has to leave the production wiring exactly
 * as it was. The annotation only ever REMOVES beans, and only in DuckDB mode.
 *
 * <p>A meta-annotation rather than the same
 * {@link ConditionalOnProperty @ConditionalOnProperty} copied onto seventeen
 * classes, because the three attributes have to agree across all of them - a
 * single one missing {@code matchIfMissing} disappears from production.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ConditionalOnProperty(name = ExecutionEngine.PROPERTY, havingValue = ExecutionEngine.MYSQL,
		matchIfMissing = true)
public @interface ConditionalOnMysqlEngine {
}
