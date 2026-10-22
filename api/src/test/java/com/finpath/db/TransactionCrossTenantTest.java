package com.finpath.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** [+TABLE] trio, part (c), for transaction: user B cannot read, change or add rows for user A. */
class TransactionCrossTenantTest extends CrossTenantTest {

    private static final String HASH = "a".repeat(64);
    private static final String INSERT = "INSERT INTO transaction (user_id, txn_date, amount_paise, direction, "
            + "category_id, mode, dedupe_hash, idempotency_key) VALUES (?, DATE '2026-01-01', 4000, 'OUT', "
            + "(SELECT id FROM category WHERE code = 'DINING'), 'UPI', '" + HASH + "', ?)";

    @Override
    protected String table() {
        return "transaction";
    }

    @Override
    protected String ownerColumn() {
        return "user_id";
    }

    @Override
    protected void seedRowFor(Connection c, UUID owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, name, email) VALUES (?, 'T', ?) ON CONFLICT DO NOTHING")) {
            ps.setObject(1, owner);
            ps.setString(2, owner + "@example.test");
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setObject(1, owner);
            ps.setString(2, "seed-" + UUID.randomUUID());
            ps.executeUpdate();
        }
    }

    @Test
    void bCannotUpdateAsRowAndCannotReassignItToThemselves() throws SQLException {
        try (Connection c = TestDb.asAppUser(B); Statement s = c.createStatement()) {
            assertThat(s.executeUpdate("UPDATE transaction SET amount_paise = 1 WHERE user_id = '" + A + "'"))
                    .isZero();
            assertThat(s.executeUpdate("UPDATE transaction SET user_id = '" + B + "' WHERE user_id = '" + A + "'"))
                    .isZero();
        }
        try (Connection c = TestDb.admin(); Statement s = c.createStatement();
                java.sql.ResultSet rs = s.executeQuery("SELECT count(*) FROM transaction WHERE user_id = '" + A
                        + "' AND amount_paise = 4000")) {
            rs.next();
            assertThat(rs.getLong(1)).isPositive();
        }
    }

    @Test
    void insertForAnotherUserIsRejected() throws SQLException {
        try (Connection c = TestDb.asAppUser(B); PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setObject(1, A);
            ps.setString(2, "forged-" + UUID.randomUUID());
            assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
        }
    }

    @Test
    void insertWithoutConsentIsRejectedEvenForOneself() throws SQLException {
        try (Connection c = TestDb.asAppUser(B); PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setObject(1, B);
            ps.setString(2, "noconsent-" + UUID.randomUUID());
            assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
        }
    }

    @Test
    void sameIdempotencyKeyIsAllowedForTwoUsersButNotTwiceForOne() throws SQLException {
        String key = "shared-key-" + UUID.randomUUID();
        try (Connection c = TestDb.admin()) {
            for (UUID u : new UUID[] {A, B}) {
                try (PreparedStatement ps = c.prepareStatement(INSERT)) {
                    ps.setObject(1, u);
                    ps.setString(2, key);
                    assertThat(ps.executeUpdate()).isEqualTo(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(INSERT)) {
                ps.setObject(1, A);
                ps.setString(2, key);
                assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class)
                        .hasMessageContaining("transaction_user_idempotency");
            }
        }
    }
}
