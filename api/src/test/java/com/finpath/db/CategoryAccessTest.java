package com.finpath.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * category is shared reference data (no personal data, no RLS): app_user may read the seeded
 * taxonomy but cannot change it. Needs the scratch database; skipped with the reason without one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CategoryAccessTest {

    @BeforeAll
    void migrate() {
        TestDb.requireAndMigrate();
    }

    private static long count(Connection c, String where) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT count(*) FROM category " + where)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void appUserSeesEverySeededRowRegardlessOfUser() throws SQLException {
        long all;
        try (Connection c = TestDb.admin()) {
            all = count(c, "");
        }
        assertThat(all).isGreaterThanOrEqualTo(21);
        try (Connection a = TestDb.asAppUser(UUID.randomUUID()); Connection none = TestDb.asAppUser(null)) {
            assertThat(count(a, "")).isEqualTo(all);
            assertThat(count(none, "")).isEqualTo(all);
        }
    }

    @Test
    void appUserCannotInsertUpdateOrDelete() throws SQLException {
        try (Connection c = TestDb.asAppUser(UUID.randomUUID()); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO category (code, name, sort_order) "
                    + "VALUES ('HACK', 'Hack', 1)")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.executeUpdate("UPDATE category SET name = 'x'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
            assertThatThrownBy(() -> s.executeUpdate("DELETE FROM category")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("permission denied");
        }
        try (Connection c = TestDb.admin()) {
            assertThat(count(c, "WHERE code = 'HACK' OR name = 'x'")).isZero();
        }
    }

    @Test
    void codesAreUniqueAndUppercase() throws SQLException {
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT count(*) - count(DISTINCT code), "
                    + "count(*) FILTER (WHERE code <> upper(code)) FROM category")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
                assertThat(rs.getLong(2)).isZero();
            }
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO category (code, name, sort_order) "
                    + "VALUES ('DINING', 'dup', 1)")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("duplicate key");
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO category (code, name, sort_order) "
                    + "VALUES ('lower', 'l', 1)")).isInstanceOf(SQLException.class)
                    .hasMessageContaining("check constraint");
        }
    }
}
