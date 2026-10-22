package com.finpath.db;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;

/**
 * Scratch Postgres named by FINPATH_TEST_DB_URL / _USER / _PASSWORD (a superuser; the database is
 * wiped and re-migrated). With none configured the calling test is reported as skipped with the
 * reason, never as passed.
 */
public final class TestDb {

    public static final String URL = System.getenv("FINPATH_TEST_DB_URL");
    public static final String USER = System.getenv().getOrDefault("FINPATH_TEST_DB_USER", "postgres");
    public static final String PASSWORD = System.getenv().getOrDefault("FINPATH_TEST_DB_PASSWORD", "");

    private TestDb() {
    }

    /** Skips unless a scratch database is configured, then wipes and migrates it. */
    public static void requireAndMigrate() {
        assumeTrue(URL != null, "SKIPPED: FINPATH_TEST_DB_URL not set, RLS not exercised against a database");
        assumeTrue(URL.toLowerCase().matches(".*/([^/?]*[_-])?(test|scratch)([_-][^/?]*)?(\\?.*)?$"),
                "SKIPPED: refusing to wipe a database whose name lacks a 'test' or 'scratch' word");
        Flyway.configure().dataSource(URL, USER, PASSWORD).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(URL, USER, PASSWORD).load().migrate();
    }

    /** Superuser connection (bypasses RLS): for seeding and metadata queries. */
    public static Connection admin() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    /** Autocommit connection running as app_user with the given current user (null = unset). */
    public static Connection asAppUser(UUID user) throws SQLException {
        Connection c = admin();
        try (Statement s = c.createStatement()) {
            s.execute("SET ROLE app_user");
            if (user != null) {
                s.execute("SELECT set_config('app.current_user_id', '" + user + "', false)");
            }
        }
        return c;
    }
}
