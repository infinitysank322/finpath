package com.finpath.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** [+TABLE] trio, part (c), for reminder_sent: B cannot read, delete or add rows for A; no consent, no insert. */
class ReminderSentCrossTenantTest extends CrossTenantTest {

    @Override
    protected String table() {
        return "reminder_sent";
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
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO reminder_sent (user_id, reminder_key, due_date) "
                + "VALUES (?, 'cycle_start', DATE '2026-10-25')")) {
            ps.setObject(1, owner);
            ps.executeUpdate();
        }
    }

    @Test
    void anInsertWithoutConsentIsRefusedByThePolicy() throws SQLException {
        try (Connection c = TestDb.asAppUser(A);
                Statement s = c.createStatement()) {
            // A has no consent row: finpath_has_consent() is false, so WITH CHECK fails even for the owner.
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO reminder_sent (user_id, reminder_key, due_date) "
                    + "VALUES ('" + A + "', 'cycle_start', DATE '2026-11-25')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("row-level security");
        }
    }

    @Test
    void updateIsNotGrantedToTheAppRole() throws SQLException {
        try (Connection c = TestDb.asAppUser(A); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("UPDATE reminder_sent SET due_date = DATE '2030-01-01'"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
    }

    @Test
    void theKeyCannotCarryFreeText() throws SQLException {
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            assertThatThrownBy(() -> s.executeUpdate("INSERT INTO reminder_sent (user_id, reminder_key, due_date) "
                    + "VALUES ('" + A + "', 'HDFC card 4111111111111111', DATE '2026-12-25')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("reminder_key");
            assertThat(s.executeUpdate("INSERT INTO reminder_sent (user_id, reminder_key, due_date) VALUES ('" + A
                    + "', 'loan_due:" + UUID.randomUUID() + "', DATE '2026-12-25')")).isEqualTo(1);
        }
    }
}
