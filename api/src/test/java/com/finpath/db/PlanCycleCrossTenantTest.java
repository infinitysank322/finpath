package com.finpath.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/** [+TABLE] trio, part (c), for plan_cycle: user B cannot read, change or add rows for user A. */
class PlanCycleCrossTenantTest extends CrossTenantTest {

    @Override
    protected String table() {
        return "plan_cycle";
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
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO plan_cycle (user_id, cycle_start, cycle_end, "
                + "start_day) VALUES (?, DATE '2026-09-25', DATE '2026-10-25', 25)")) {
            ps.setObject(1, owner);
            ps.executeUpdate();
        }
    }
}
