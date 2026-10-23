package com.finpath.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/** [+TABLE] trio, part (c), for net_worth_snapshot: user B cannot read, change or add rows for user A. */
class NetWorthSnapshotCrossTenantTest extends CrossTenantTest {

    @Override
    protected String table() {
        return "net_worth_snapshot";
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
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO net_worth_snapshot (user_id, plan_cycle_id, "
                + "cycle_start, assets_paise, liabilities_paise, net_worth_paise) "
                + "VALUES (?, ?, DATE '2026-09-25', 100, 40, 60)")) {
            ps.setObject(1, owner);
            ps.setObject(2, cycle);
            ps.executeUpdate();
        }
    }
}
