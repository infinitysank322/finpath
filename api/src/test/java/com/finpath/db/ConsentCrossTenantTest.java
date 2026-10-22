package com.finpath.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/** [+TABLE] trio, part (c), for consent: user B cannot read, change or add rows for user A. */
class ConsentCrossTenantTest extends CrossTenantTest {

    @Override
    protected String table() {
        return "consent";
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
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO consent (user_id, purpose, statement_version, "
                + "purpose_statement) VALUES (?, 'FINANCIAL_PLANNING', 'v1', 'text')")) {
            ps.setObject(1, owner);
            ps.executeUpdate();
        }
    }
}
