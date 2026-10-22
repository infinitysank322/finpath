package com.finpath.dpdp;

import static org.assertj.core.api.Assertions.assertThat;

import com.finpath.db.TestDb;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import com.finpath.auth.AuthProperties;
import com.finpath.auth.DeletedUsers;
import com.finpath.auth.OneTimeCodeStore;
import com.finpath.auth.RefreshTokenStore;
import com.finpath.auth.SessionRevoker;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

class UnconsentedUserPurgeDbTest {

    private final AuthProperties props = new AuthProperties("x".repeat(AuthProperties.MIN_SECRET_BYTES),
            List.of("http://localhost:4200/auth/callback"), Duration.ofMinutes(15), Duration.ofDays(30),
            Duration.ofSeconds(60));
    private final RefreshTokenStore refresh = new RefreshTokenStore(props);
    private final OneTimeCodeStore codes = new OneTimeCodeStore(props);
    private final DeletedUsers deleted = new DeletedUsers(props);

    private UnconsentedUserPurge purger(ObjectProvider<JdbcTemplate> provider) {
        return new UnconsentedUserPurge(provider, new SessionRevoker(refresh, codes, deleted), Duration.ofDays(7));
    }

    private static void user(Statement s, UUID id, String age) throws SQLException {
        s.execute("INSERT INTO users (id, name, email, created_at) VALUES ('" + id + "', 'N', '" + id
                + "@example.test', now() - interval '" + age + "')");
    }

    private static boolean exists(UUID id) throws SQLException {
        try (Connection c = TestDb.admin(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM users WHERE id = '" + id + "'")) {
            rs.next();
            return rs.getLong(1) == 1;
        }
    }

    @Test
    void purgesNeverConsentedAndWithdrawnUsersAfterGraceAndKeepsEveryoneElse() throws Exception {
        DpdpFixture f = new DpdpFixture();
        UUID consented = UUID.randomUUID();
        UUID abandonedOld = UUID.randomUUID();
        UUID abandonedFresh = UUID.randomUUID();
        UUID withdrawnOld = UUID.randomUUID();
        UUID withdrawnRecently = UUID.randomUUID();
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            user(s, consented, "30 days");
            user(s, abandonedOld, "30 days");
            user(s, abandonedFresh, "1 minute");
            user(s, withdrawnOld, "30 days");
            user(s, withdrawnRecently, "30 days");
            String cols = "INSERT INTO consent (user_id, purpose, statement_version, purpose_statement, granted_at, withdrawn_at) VALUES ('";
            s.execute(cols + consented + "', 'FINANCIAL_PLANNING', 'v', 't', now() - interval '29 days', NULL)");
            s.execute(cols + withdrawnOld + "', 'FINANCIAL_PLANNING', 'v', 't', now() - interval '29 days', now() - interval '20 days')");
            s.execute(cols + withdrawnRecently + "', 'FINANCIAL_PLANNING', 'v', 't', now() - interval '29 days', now() - interval '1 hour')");
        }
        ObjectProvider<JdbcTemplate> provider = new org.springframework.beans.factory.support.StaticListableBeanFactory(
                java.util.Map.of("jdbc", f.jdbc)).getBeanProvider(JdbcTemplate.class);
        String purgedRefresh = refresh.issue(abandonedOld);
        String keptRefresh = refresh.issue(consented);
        int purged = purger(provider).purge();

        assertThat(purged).isEqualTo(2);
        assertThat(exists(consented)).isTrue();
        assertThat(exists(abandonedFresh)).as("inside grace").isTrue();
        assertThat(exists(withdrawnRecently)).as("withdrawal inside grace").isTrue();
        assertThat(refresh.rotate(purgedRefresh)).as("purged user's refresh token").isEmpty();
        assertThat(deleted.isDeleted(abandonedOld)).as("purged user's JWTs are refused").isTrue();
        assertThat(deleted.isDeleted(withdrawnOld)).isTrue();
        assertThat(refresh.rotate(keptRefresh)).as("kept user unaffected").isPresent();
        assertThat(deleted.isDeleted(consented)).isFalse();
        assertThat(exists(abandonedOld)).isFalse();
        assertThat(exists(withdrawnOld)).isFalse();
        try (Connection c = TestDb.admin(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM consent WHERE user_id = '" + withdrawnOld + "'")) {
            rs.next();
            assertThat(rs.getLong(1)).as("consent rows go with the user").isZero();
        }
    }

    @Test
    void graceHasAOneHourFloor() throws Exception {
        DpdpFixture f = new DpdpFixture();
        UUID fresh = UUID.randomUUID();
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            user(s, fresh, "1 minute");
        }
        assertThat(f.jdbc.queryForObject("SELECT cardinality(finpath_purge_unconsented_users(interval '0'))", Integer.class)).isZero();
        assertThat(exists(fresh)).isTrue();
    }

    @Test
    void appUserCannotRunThePurge() throws Exception {
        TestDb.requireAndMigrate();
        try (Connection c = TestDb.asAppUser(UUID.randomUUID()); Statement s = c.createStatement()) {
            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> s.executeQuery("SELECT finpath_purge_unconsented_users(interval '1 day')"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
        }
    }

    @Test
    void withoutADatabaseThePurgeIsANoOp() {
        ObjectProvider<JdbcTemplate> none = new org.springframework.beans.factory.support.StaticListableBeanFactory()
                .getBeanProvider(JdbcTemplate.class);
        assertThat(purger(none).purge()).isZero();
    }
}
