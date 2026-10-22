package com.finpath.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertySource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Neon scales to zero, so the first connection after an idle period can take many seconds. This
 * simulates a 12 s first connect with an in-JVM driver (no network, no database) and the real prod
 * Hikari settings read from application.yml, so drifting those settings fails this test.
 * Real wall-clock time on purpose: Hikari's timeouts use the system clock, so compressing time
 * would not test them.
 */
class ColdStartFirstConnectTest {

    private static final long FIRST_CONNECT_MILLIS = 12_000;

    /** Sleeps on the first connect only, then behaves like a healthy database. */
    static final class SlowFirstConnectDriver implements Driver {
        static final String URL = "jdbc:stub:coldstart";
        final AtomicInteger connects = new AtomicInteger();

        @Override
        public Connection connect(String url, Properties info) throws SQLException {
            if (!acceptsURL(url)) {
                return null;
            }
            if (connects.getAndIncrement() == 0) {
                try {
                    Thread.sleep(FIRST_CONNECT_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("interrupted");
                }
            }
            return healthyConnection();
        }

        @Override
        public boolean acceptsURL(String url) {
            return URL.equals(url);
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public java.util.logging.Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
    }

    /** Minimal Connection: every call returns a neutral default; statements "execute" fine. */
    private static Connection healthyConnection() {
        Object statement = Proxy.newProxyInstance(
                ColdStartFirstConnectTest.class.getClassLoader(),
                new Class<?>[] {java.sql.Statement.class},
                (p, m, a) -> neutral(m.getReturnType()));
        return (Connection) Proxy.newProxyInstance(
                ColdStartFirstConnectTest.class.getClassLoader(),
                new Class<?>[] {Connection.class},
                (p, m, a) -> switch (m.getName()) {
                    case "createStatement", "prepareStatement" -> statement;
                    case "isValid" -> true;
                    case "toString" -> "stub-connection";
                    case "hashCode" -> System.identityHashCode(p);
                    case "equals" -> p == a[0];
                    default -> neutral(m.getReturnType());
                });
    }

    private static Object neutral(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        return null;
    }

    @Test
    void twelveSecondFirstConnectSucceedsWithoutAnyErrorSurfacing() throws Exception {
        MockEnvironment env = new MockEnvironment();
        for (PropertySource<?> doc : ProdProfileYaml.prodDocuments()) {
            env.getPropertySources().addLast(doc);
        }
        HikariConfig config = Binder.get(env)
                .bind("spring.datasource.hikari", Bindable.of(HikariConfig.class))
                .get();
        config.setJdbcUrl(SlowFirstConnectDriver.URL);
        // With the prod initialization-fail-timeout the pool constructor itself waits out the
        // first connect, which would leave connection-timeout unexercised. Skip that wait so the
        // 12 s lands inside getConnection() and is bounded by the prod connection-timeout.
        assertThat(config.getConnectionTimeout()).isGreaterThanOrEqualTo(FIRST_CONNECT_MILLIS);
        config.setInitializationFailTimeout(-1);
        // The stub never checks credentials; Hikari does not need them.

        SlowFirstConnectDriver driver = new SlowFirstConnectDriver();
        DriverManager.registerDriver(driver);

        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        long started = System.nanoTime();
        try (HikariDataSource ds = new HikariDataSource(config)) {
            try (Connection c = ds.getConnection()) {
                assertThat(c.isValid(1)).isTrue();
            }
        } finally {
            root.detachAppender(logs);
            DriverManager.deregisterDriver(driver);
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;

        assertThat(elapsedMillis).as("the simulated cold start really happened")
                .isGreaterThanOrEqualTo(FIRST_CONNECT_MILLIS - 500);
        assertThat(logs.list).as("no ERROR-level log for a slow first connect")
                .noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.ERROR));
    }
}
