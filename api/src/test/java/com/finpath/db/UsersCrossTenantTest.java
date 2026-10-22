package com.finpath.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

class UsersCrossTenantTest extends CrossTenantTest {

    @Override
    protected String table() {
        return "users";
    }

    @Override
    protected String ownerColumn() {
        return "id";
    }

    @Override
    protected void seedRowFor(Connection c, UUID owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO users (id, name, email) VALUES (?, 'T', ?)")) {
            ps.setObject(1, owner);
            ps.setString(2, owner + "@example.test");
            ps.executeUpdate();
        }
    }
}
