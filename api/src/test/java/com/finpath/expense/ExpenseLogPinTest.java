package com.finpath.expense;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/** Constraint-violation messages carry the failing row, so the loggers that print them stay at INFO. */
class ExpenseLogPinTest {

    @Test
    void transactionAndJdbcLoggersArePinnedToInfoInEveryProfile() throws Exception {
        var sources = new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"));
        PropertySource<?> shared = sources.get(0);
        assertThat(shared.getProperty("logging.level.org.springframework.transaction")).isEqualTo("INFO");
        assertThat(shared.getProperty("logging.level.org.springframework.jdbc")).isEqualTo("INFO");
        for (PropertySource<?> s : sources) {   // no profile document may raise them again
            assertThat(s.getProperty("logging.level.org.springframework.transaction")).isIn(null, "INFO");
            assertThat(s.getProperty("logging.level.org.springframework.jdbc")).isIn(null, "INFO");
        }
    }
}
