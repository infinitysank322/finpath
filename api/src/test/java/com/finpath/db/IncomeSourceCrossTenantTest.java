package com.finpath.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** [+TABLE] trio, part (c), for income_source: user B cannot read, change or add rows for user A. */
class IncomeSourceCrossTenantTest extends CrossTenantTest {

    @Override
    protected String table() {
        return "income_source";
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
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO income_source (user_id, type, frequency, "
                + "amount_paise, start_date) VALUES (?, 'SALARY', 'MONTHLY', 10000000, DATE '2026-01-01')")) {
            ps.setObject(1, owner);
            ps.executeUpdate();
        }
    }

    @Test
    void bCannotUpdateAsRowAndCannotReassignItToThemselvesOrBack() throws SQLException {
        try (Connection c = TestDb.asAppUser(B); Statement s = c.createStatement()) {
            assertThat(s.executeUpdate("UPDATE income_source SET amount_paise = 1 WHERE user_id = '" + A + "'"))
                    .isZero();
            assertThat(s.executeUpdate("UPDATE income_source SET user_id = '" + B + "' WHERE user_id = '" + A
                    + "'")).isZero();
        }
        try (Connection c = TestDb.admin(); Statement s = c.createStatement();
                java.sql.ResultSet rs = s.executeQuery("SELECT count(*) FROM income_source WHERE user_id = '" + A
                        + "' AND amount_paise = 10000000")) {
            rs.next();
            assertThat(rs.getLong(1)).isPositive();
        }
    }

    @Test
    void insertWithoutConsentIsRejectedEvenForOneself() throws SQLException {
        try (Connection c = TestDb.asAppUser(B); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO income_source (user_id, type, frequency, amount_paise, start_date) "
                        + "VALUES (?, 'SALARY', 'MONTHLY', 1, DATE '2026-01-01')")) {
            ps.setObject(1, B);
            assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
        }
    }
}
