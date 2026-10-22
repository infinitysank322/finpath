package com.finpath.rls;

import javax.sql.DataSource;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Replaces Boot's DataSourceTransactionManager with {@link RlsTransactionManager}. An
 * auto-configuration (ordered before Boot's) so the DataSource condition is evaluated after the
 * datasource exists; with no datasource (ci profile, unit tests) nothing is registered.
 */
@AutoConfiguration(after = DataSourceAutoConfiguration.class, before = DataSourceTransactionManagerAutoConfiguration.class)
@ConditionalOnSingleCandidate(DataSource.class)
public class RlsAutoConfiguration {

    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
        return new RlsTransactionManager(dataSource);
    }

    /** Runs after Flyway (an ApplicationRunner starts once the context is refreshed). */
    @Bean
    ApplicationRunner rlsRoleGuard(DataSource dataSource) {
        return args -> RlsRoleGuard.verify(dataSource);
    }
}
