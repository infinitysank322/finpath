package com.finpath.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * [+TABLE] trio, part (c): user B cannot read user A's row. Runs against a real Postgres named by
 * FINPATH_TEST_DB_URL / _USER / _PASSWORD (a scratch database: it is wiped). With no such
 * database the tests are reported as skipped with the reason, never as passed; CI has no database,
 * so the owner runs this locally before merging dev to master.
 */
class UsersRlsTest {

    private static final String URL = System.getenv("FINPATH_TEST_DB_URL");
    private static final String USER = System.getenv().getOrDefault("FINPATH_TEST_DB_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("FINPATH_TEST_DB_PASSWORD", "");

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @BeforeAll
    static void migrateAndSeed() throws SQLException {
        assumeTrue(URL != null, "SKIPPED: FINPATH_TEST_DB_URL not set, RLS not exercised against a database");
        assumeTrue(URL.toLowerCase().matches(".*/[^/?]*(test|scratch)[^/?]*(\\?.*)?$"),
                "SKIPPED: refusing to wipe a database whose name lacks 'test' or 'scratch'");
        Flyway.configure().dataSource(URL, USER, PASSWORD).cleanDisabled(false).load().clean();
        Flyway.configure().dataSource(URL, USER, PASSWORD).load().migrate();
        try (Connection c = connect(); Statement s = c.createStatement()) {
            // Superuser seeds both rows; superusers bypass RLS.
            s.execute("INSERT INTO users (id, name, email) VALUES ('" + A + "', 'A', 'a@example.test'), ('"
                    + B + "', 'B', 'b@example.test')");
        }
    }

    private static Connection connect() throws SQLException {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }

    /** A connection running as the app role with the given current user (null = unset). */
    private static Connection asAppUser(UUID user) throws SQLException {
        Connection c = connect();
        try (Statement s = c.createStatement()) {
            s.execute("SET ROLE app_user");
            if (user != null) {
                s.execute("SELECT set_config('app.current_user_id', '" + user + "', false)");
            }
        }
        return c;
    }

    private static long count(Connection c, String where) throws SQLException {
        try (Statement s = c.createStatement();
                var rs = s.executeQuery("SELECT count(*) FROM users " + where)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void userBCannotSelectUserAsRow() throws SQLException {
        try (Connection c = asAppUser(B)) {
            assertThat(count(c, "WHERE id = '" + A + "'")).isZero();
            assertThat(count(c, "")).isEqualTo(1);
            assertThat(count(c, "WHERE id = '" + B + "'")).isEqualTo(1);
        }
    }

    @Test
    void userACanSelectOwnRowOnly() throws SQLException {
        try (Connection c = asAppUser(A)) {
            assertThat(count(c, "")).isEqualTo(1);
            assertThat(count(c, "WHERE id = '" + A + "'")).isEqualTo(1);
        }
    }

    @Test
    void unsetUserSeesNothing() throws SQLException {
        try (Connection c = asAppUser(null)) {
            assertThat(count(c, "")).isZero();
        }
    }

    @Test
    void userBCannotUpdateOrDeleteUserA() throws SQLException {
        try (Connection c = asAppUser(B); Statement s = c.createStatement()) {
            assertThat(s.executeUpdate("UPDATE users SET name = 'x' WHERE id = '" + A + "'")).isZero();
            assertThat(s.executeUpdate("DELETE FROM users WHERE id = '" + A + "'")).isZero();
        }
    }

    @Test
    void userBCannotInsertARowForAnotherUser() throws SQLException {
        try (Connection c = asAppUser(B); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.execute(
                    "INSERT INTO users (id, name, email) VALUES ('" + UUID.randomUUID() + "', 'C', 'c@example.test')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
        }
    }

    @Test
    void newRowDefaultsToCycleDay25AndInr() throws SQLException {
        try (Connection c = connect(); Statement s = c.createStatement();
                var rs = s.executeQuery("SELECT cycle_start_day, currency FROM users WHERE id = '" + A + "'")) {
            rs.next();
            assertThat(rs.getShort(1)).isEqualTo((short) 25);
            assertThat(rs.getString(2).trim()).isEqualTo("INR");
        }
    }
}
