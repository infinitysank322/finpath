package com.finpath.rls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finpath.db.TestDb;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The connection initialiser against a real database: inside a transaction the role is app_user and
 * the caller's id is set; once it ends neither survives on the (single, reused) connection.
 */
class RlsTransactionManagerTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    private static JdbcTemplate jdbc;
    private static Optional<UUID> caller = Optional.empty();
    private static TransactionTemplate tx;

    @BeforeAll
    static void setUp() throws SQLException {
        TestDb.requireAndMigrate();
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO users (id, name, email) VALUES ('" + A + "', 'A', 'a@example.test'), ('"
                    + B + "', 'B', 'b@example.test')");
        }
        // One physical connection, so any leak between transactions would be visible.
        var ds = new org.springframework.jdbc.datasource.SingleConnectionDataSource(
                TestDb.URL, TestDb.USER, TestDb.PASSWORD, true);
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new RlsTransactionManager(ds, () -> caller));
    }

    private static <T> T inTx(Optional<UUID> user, java.util.function.Supplier<T> body) {
        caller = user;
        return tx.execute(status -> body.get());
    }

    @Test
    void transactionRunsAsAppUserWithTheCallersId() {
        String who = inTx(Optional.of(A), () -> jdbc.queryForObject("SELECT current_user", String.class));
        assertThat(who).isEqualTo("app_user");
        UUID seen = inTx(Optional.of(A), () -> jdbc.queryForObject("SELECT id FROM users", UUID.class));
        assertThat(seen).isEqualTo(A);
    }

    @Test
    void rawQueryForAnotherUsersIdReturnsNothing() {
        Long n = inTx(Optional.of(B),
                () -> jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Long.class, A));
        assertThat(n).isZero();
    }

    @Test
    void noAuthenticatedUserFailsClosed() {
        assertThat(inTx(Optional.empty(), () -> jdbc.queryForObject("SELECT count(*) FROM users", Long.class)))
                .isZero();
    }

    @Test
    void nothingLeaksToTheNextTransactionOrToStatementsOutsideOne() {
        inTx(Optional.of(A), () -> jdbc.queryForObject("SELECT count(*) FROM users", Long.class));
        // Same physical connection, no transaction: the role and the setting are gone.
        assertThat(jdbc.queryForObject("SELECT current_user <> 'app_user'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT coalesce(current_setting('app.current_user_id', true), '')",
                String.class)).isEmpty();
        // A following transaction for a different user sees only that user.
        UUID seen = inTx(Optional.of(B), () -> jdbc.queryForObject("SELECT id FROM users", UUID.class));
        assertThat(seen).isEqualTo(B);
    }

    @Test
    void roleGuardAcceptsTheMigratedDatabaseAndRejectsAMissingRole() throws SQLException {
        var ds = new DriverManagerDataSource(TestDb.URL, TestDb.USER, TestDb.PASSWORD);
        RlsRoleGuard.verify(ds);
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            s.execute("ALTER ROLE app_user BYPASSRLS");
            try {
                assertThatThrownBy(() -> RlsRoleGuard.verify(ds)).isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("BYPASSRLS");
            } finally {
                s.execute("ALTER ROLE app_user NOBYPASSRLS");
            }
        }
    }
}
