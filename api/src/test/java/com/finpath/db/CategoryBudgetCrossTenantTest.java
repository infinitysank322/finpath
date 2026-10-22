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

/** [+TABLE] trio, part (c), for category_budget: user B cannot read, change, delete or add rows for user A. */
class CategoryBudgetCrossTenantTest extends CrossTenantTest {

    private static final String INSERT = "INSERT INTO category_budget (user_id, cycle_start, category_id, "
            + "budget_paise) VALUES (?, DATE '2026-10-01', (SELECT id FROM category WHERE code = 'DINING'), 700000)";

    @Override
    protected String table() {
        return "category_budget";
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
                ResultSet rs = s.executeQuery("SELECT count(*) FROM category_budget WHERE user_id = '" + A
                        + "' AND budget_paise = 700000 AND last_alert_level = 'OK'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void bCannotUpdateAsRowAndCannotReassignItToThemselves() throws SQLException {
        try (Connection c = TestDb.asAppUser(B); Statement s = c.createStatement()) {
            assertThat(s.executeUpdate("UPDATE category_budget SET budget_paise = 1 WHERE user_id = '" + A + "'"))
                    .isZero();
            assertThat(s.executeUpdate("UPDATE category_budget SET last_alert_level = 'BREACH' "
                    + "WHERE user_id = '" + A + "'")).isZero();
            assertThat(s.executeUpdate("UPDATE category_budget SET user_id = '" + B + "' WHERE user_id = '" + A
                    + "'")).isZero();
        }
        assertThat(countOfAsRows()).isPositive();
    }

    @Test
    void bCannotDeleteAsRow() throws SQLException {
        try (Connection c = TestDb.asAppUser(B); Statement s = c.createStatement()) {
            assertThat(s.executeUpdate("DELETE FROM category_budget WHERE user_id = '" + A + "'")).isZero();
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
