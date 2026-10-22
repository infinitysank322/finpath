package com.finpath.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** [+TABLE] trio, part (c), for loan: user B cannot read, change, delete or add rows for user A. */
class LoanCrossTenantTest extends CrossTenantTest {

    private static final String INSERT = "INSERT INTO loan (user_id, lender, type, principal_outstanding_paise, "
            + "rate, emi_paise, emis_left) VALUES (?, 'Test Lender', 'emi', 1000000, 0.08, 20000, 60)";

    @Override
    protected String table() {
        return "loan";
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
            ps.executeUpdate();
        }
    }

    private long countOfAsRows() throws SQLException {
        try (Connection c = TestDb.admin(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM loan WHERE user_id = '" + A
                        + "' AND principal_outstanding_paise = 1000000 AND lender = 'Test Lender'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void bCannotUpdateAsRowAndCannotReassignItToThemselves() throws SQLException {
        try (Connection c = TestDb.asAppUser(B); Statement s = c.createStatement()) {
            assertThat(s.executeUpdate("UPDATE loan SET principal_outstanding_paise = 1 WHERE user_id = '" + A
                    + "'")).isZero();
            assertThat(s.executeUpdate("UPDATE loan SET user_id = '" + B + "' WHERE user_id = '" + A + "'"))
                    .isZero();
        }
        assertThat(countOfAsRows()).isPositive();
    }

    @Test
    void bCannotDeleteAsRow() throws SQLException {
        try (Connection c = TestDb.asAppUser(B); Statement s = c.createStatement()) {
            assertThat(s.executeUpdate("DELETE FROM loan WHERE user_id = '" + A + "'")).isZero();
        }
        assertThat(countOfAsRows()).isPositive();
    }

    @Test
    void insertForAnotherUserIsRejected() throws SQLException {
        try (Connection c = TestDb.asAppUser(B); PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setObject(1, A);
            assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
        }
    }

    @Test
    void insertWithoutConsentIsRejectedEvenForOneself() throws SQLException {
        try (Connection c = TestDb.asAppUser(B); PreparedStatement ps = c.prepareStatement(INSERT)) {
            ps.setObject(1, B);
            assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
        }
    }
}
