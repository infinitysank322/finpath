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
 * Reusable base for the [+TABLE] "user B cannot read user A's row" criterion. Every per-user
 * table gets a subclass that names the table and owner column and seeds one row for a user.
 *
 * <p>The queries are deliberately raw SQL with no user predicate: that is a repository whose
 * service-layer scoping has been bypassed, so only the database policy stands between B and A's
 * rows. Needs a scratch database (see {@link TestDb}); skipped, with the reason, without one.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class CrossTenantTest {

    protected static final UUID A = UUID.randomUUID();
    protected static final UUID B = UUID.randomUUID();

    /** The table under test. */
    protected abstract String table();

    /** Column holding the owning user's id. */
    protected abstract String ownerColumn();

    /** Inserts one row owned by {@code owner} as a superuser; may insert parents (including the user row). */
    protected abstract void seedRowFor(Connection admin, UUID owner) throws SQLException;

    @BeforeAll
    void migrateAndSeed() throws SQLException {
        TestDb.requireAndMigrate();
        try (Connection c = TestDb.admin()) {
            seedRowFor(c, A);
            seedRowFor(c, B);
        }
    }

    private long count(Connection c, String where) throws SQLException {
        try (Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table() + " " + where)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void bCannotSelectAsRowEvenByNamingAsId() throws SQLException {
        try (Connection c = TestDb.asAppUser(B)) {
            assertThat(count(c, "WHERE " + ownerColumn() + " = '" + A + "'")).isZero();
            assertThat(count(c, "")).isEqualTo(count(c, "WHERE " + ownerColumn() + " = '" + B + "'"));
        }
    }

    @Test
    void eachUserSeesOwnRows() throws SQLException {
        try (Connection c = TestDb.asAppUser(A)) {
            assertThat(count(c, "WHERE " + ownerColumn() + " = '" + A + "'")).isPositive();
        }
    }

    @Test
    void unsetUserSeesNothing() throws SQLException {
        try (Connection c = TestDb.asAppUser(null)) {
            assertThat(count(c, "")).isZero();
        }
    }

    @Test
    void bCannotUpdateOrDeleteAsRows() throws SQLException {
        try (Connection c = TestDb.asAppUser(B); Statement s = c.createStatement()) {
            assertThat(s.executeUpdate("DELETE FROM " + table() + " WHERE " + ownerColumn() + " = '" + A + "'"))
                    .isZero();
        }
        try (Connection c = TestDb.admin()) {
            assertThat(count(c, "WHERE " + ownerColumn() + " = '" + A + "'")).isPositive();
        }
    }

    @Test
    void bCannotInsertARowOwnedByA() throws SQLException {
        try (Connection c = TestDb.asAppUser(B)) {
            assertThatThrownBy(() -> {
                c.setAutoCommit(true);
                seedRowFor(c, UUID.randomUUID());
            }).isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
        }
    }
}
