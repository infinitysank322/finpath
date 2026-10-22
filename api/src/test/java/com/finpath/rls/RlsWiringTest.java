package com.finpath.rls;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

/** The wiring, without a database: with a datasource the transaction manager is the RLS one. */
class RlsWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(RlsAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class));

    @Test
    void withADataSourceTheTransactionManagerIsTheRlsOne() {
        runner.withBean(javax.sql.DataSource.class, () -> new DriverManagerDataSource("jdbc:postgresql://x.invalid/t"))
                .run(ctx -> assertThat(ctx.getBean(PlatformTransactionManager.class))
                        .isInstanceOf(RlsTransactionManager.class));
    }

    @Test
    void withoutADataSourceNothingIsRegistered() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(PlatformTransactionManager.class));
    }
}
