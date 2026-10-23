package com.finpath.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/** [+TABLE] trio, part (c), for checklist_item: user B cannot read, change or add rows for user A. */
class ChecklistItemCrossTenantTest extends CrossTenantTest {

    @Override
    protected String table() {
        return "checklist_item";
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
        UUID cycle = UUID.randomUUID();
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO plan_cycle (id, user_id, cycle_start, "
                + "cycle_end, start_day, status) VALUES (?, ?, DATE '2026-09-25', DATE '2026-10-25', 25, 'CLOSED')")) {
            ps.setObject(1, cycle);
            ps.setObject(2, owner);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO checklist_item (user_id, plan_cycle_id, "
                + "type, source_ref, amount_paise) VALUES (?, ?, 'EMI', 'loan-ref', 100)")) {
            ps.setObject(1, owner);
            ps.setObject(2, cycle);
            ps.executeUpdate();
        }
    }
}
