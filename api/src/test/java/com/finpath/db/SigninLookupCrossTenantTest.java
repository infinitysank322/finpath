package com.finpath.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** T29 follow-up: the SECURITY DEFINER sign-in lookup reveals an id and nothing else about another user. */
class SigninLookupCrossTenantTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @BeforeAll
    static void seed() throws SQLException {
        TestDb.requireAndMigrate();
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO users (id, name, email) VALUES ('" + A + "', 'Secret Name', 'a@example.test'), ('"
                    + B + "', 'B', 'b@example.test')");
        }
    }

    @Test
    void returnsOnlyAScalarUuidAndNoOtherColumn() throws SQLException {
        try (Connection c = TestDb.admin(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT prorettype::regtype::text, proretset FROM pg_proc "
                        + "WHERE proname = 'finpath_user_id_by_email'")) {
            rs.next();
            assertThat(rs.getString(1)).isEqualTo("uuid");
            assertThat(rs.getBoolean(2)).isFalse();
        }
    }

    @Test
    void bLearnsAsIdButStillCannotReadAsRow() throws SQLException {
        try (Connection c = TestDb.asAppUser(B); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery("SELECT finpath_user_id_by_email('a@example.test')")) {
                rs.next();
                assertThat(rs.getObject(1, UUID.class)).isEqualTo(A);
            }
            try (ResultSet rs = s.executeQuery("SELECT count(*) FROM users WHERE email = 'a@example.test'")) {
                rs.next();
                assertThat(rs.getLong(1)).isZero();
            }
        }
    }

    @Test
    void unknownEmailAndPatternsYieldNull() throws SQLException {
        try (Connection c = TestDb.asAppUser(null); Statement s = c.createStatement()) {
            for (String probe : new String[] {"nobody@example.test", "%", "a@example.%"}) {
                try (ResultSet rs = s.executeQuery("SELECT finpath_user_id_by_email('" + probe + "')")) {
                    rs.next();
                    assertThat(rs.getObject(1)).as(probe).isNull();
                }
            }
        }
    }
}
