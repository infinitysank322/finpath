package com.finpath.dpdp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpath.db.TestDb;
import com.finpath.rls.RlsTransactionManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real services over the scratch database, wired the way production is: a JdbcTemplate and the RLS
 * transaction manager. {@link #actAs} stands in for the JWT principal that CurrentUser supplies.
 */
final class DpdpFixture {

    private static volatile UUID current;

    final JdbcTemplate jdbc;
    final DataSubjectService data;
    final ConsentService consents;

    DpdpFixture() {
        TestDb.requireAndMigrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(TestDb.URL, TestDb.USER, TestDb.PASSWORD);
        jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new RlsTransactionManager(ds, () -> Optional.ofNullable(current)));
        DbAccess db = new DbAccess(jdbc, tx);
        data = new DataSubjectService(db, new ObjectMapper(), Clock.systemUTC(),
                DataSubjectRegistry.exportTables(), DataSubjectRegistry.deleteTables());
        consents = new ConsentService(db);
    }

    static void actAs(UUID user) {
        current = user;
    }

    /** Superuser insert (bypasses RLS), as sign-in's users upsert would do. */
    static void insertUser(UUID id, String name) throws SQLException {
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, name, email) VALUES (?, ?, ?)")) {
            ps.setObject(1, id);
            ps.setString(2, name);
            ps.setString(3, id + "@example.test");
            ps.executeUpdate();
        }
    }
}
