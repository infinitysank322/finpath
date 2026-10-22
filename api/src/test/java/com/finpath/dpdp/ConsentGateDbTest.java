package com.finpath.dpdp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finpath.db.TestDb;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Consent is required before the first business-table write, proven against a business table. No
 * business table exists yet (T34+), so the test creates one inside a transaction that is rolled
 * back, with the policy shape every real table must use (finpath_has_consent() in WITH CHECK).
 */
class ConsentGateDbTest {

    private static final String V = ConsentStatement.CURRENT.version();

    private static boolean tryInsert(Connection c, UUID owner) throws SQLException {
        Savepoint sp = c.setSavepoint();
        try (Statement s = c.createStatement()) {
            s.execute("INSERT INTO scratch_business (user_id, amount_paise) VALUES ('" + owner + "', 100)");
            return true;
        } catch (SQLException e) {
            assertThat(e.getMessage()).contains("row-level security");
            c.rollback(sp);
            return false;
        }
    }

    @Test
    void businessWriteIsRefusedUntilConsentIsGivenAndAgainAfterItIsWithdrawn() throws Exception {
        TestDb.requireAndMigrate();
        UUID u = UUID.randomUUID();
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            c.setAutoCommit(false);
            try {
                s.execute("INSERT INTO users (id, name, email) VALUES ('" + u + "', 'U', 'u@example.test')");
                s.execute("CREATE TABLE scratch_business (user_id uuid NOT NULL REFERENCES users (id), "
                        + "amount_paise bigint NOT NULL)");
                s.execute("ALTER TABLE scratch_business ENABLE ROW LEVEL SECURITY");
                s.execute("ALTER TABLE scratch_business FORCE ROW LEVEL SECURITY");
                s.execute("CREATE POLICY scratch_self ON scratch_business "
                        + "USING (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid) "
                        + "WITH CHECK (user_id = NULLIF(current_setting('app.current_user_id', true), '')::uuid "
                        + "AND finpath_has_consent())");
                s.execute("GRANT SELECT, INSERT ON scratch_business TO app_user");
                s.execute("SET LOCAL ROLE app_user");
                s.execute("SELECT set_config('app.current_user_id', '" + u + "', true)");
                s.execute("SELECT set_config('app.consent_version', '" + V + "', true)");

                // 1. sign-in's users row exists, no consent: the first business write is refused.
                assertThat(tryInsert(c, u)).as("write before any consent").isFalse();

                // 2. consent given through the same table the API writes.
                s.execute("INSERT INTO consent (user_id, purpose, statement_version, purpose_statement) VALUES ('"
                        + u + "', 'FINANCIAL_PLANNING', '" + V + "', 't')");
                assertThat(tryInsert(c, u)).as("write after consent").isTrue();

                // 3. withdrawn: refused again.
                s.execute("UPDATE consent SET withdrawn_at = now() WHERE user_id = '" + u + "'");
                assertThat(tryInsert(c, u)).as("write after withdrawal").isFalse();
            } finally {
                c.rollback();
            }
        }
    }

    @Test
    void consentCheckSeesOnlyTheCallersOwnConsent() throws Exception {
        TestDb.requireAndMigrate();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        DpdpFixture.insertUser(a, "A");
        DpdpFixture.insertUser(b, "B");
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO consent (user_id, purpose, statement_version, purpose_statement) VALUES ('"
                    + a + "', 'FINANCIAL_PLANNING', '" + V + "', 't')");
        }
        assertThat(hasConsent(b, V)).as("B does not inherit A's consent").isFalse();
        assertThat(hasConsent(a, V)).isTrue();
        assertThat(hasConsent(null, V)).as("no user, no consent").isFalse();
    }

    private static boolean hasConsent(UUID user, String version) throws SQLException {
        try (Connection c = TestDb.asAppUser(user); Statement s = c.createStatement()) {
            if (version != null) {
                s.execute("SELECT set_config('app.consent_version', '" + version + "', false)");
            }
            try (var rs = s.executeQuery("SELECT finpath_has_consent()")) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    @Test
    void consentToAnOlderStatementVersionDoesNotPassTheSqlGateOrTheService() throws Exception {
        DpdpFixture f = new DpdpFixture();
        UUID u = UUID.randomUUID();
        DpdpFixture.insertUser(u, "U");
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            s.execute("INSERT INTO consent (user_id, purpose, statement_version, purpose_statement) VALUES ('"
                    + u + "', 'FINANCIAL_PLANNING', '2026-10-v1', 'old wording')");
        }
        assertThat(hasConsent(u, V)).as("SQL gate: stale version").isFalse();
        assertThat(hasConsent(u, "2026-10-v1")).as("gate follows the version the app requires").isTrue();
        assertThat(hasConsent(u, null)).as("unset version fails closed").isFalse();
        DpdpFixture.actAs(u);
        assertThat(f.consents.current(u)).isEmpty();
        assertThatThrownBy(() -> f.consents.require(u)).isInstanceOf(ConsentRequiredException.class);

        // Re-consenting to the current wording retires the stale row and passes both gates.
        f.consents.grant(u, ConsentStatement.CURRENT.purpose(), V);
        assertThat(hasConsent(u, V)).isTrue();
        f.consents.require(u);
        assertThat(f.jdbc.queryForObject("SELECT count(*) FROM consent WHERE user_id = ? AND withdrawn_at IS NULL",
                Long.class, u)).isEqualTo(1L);
    }

    @Test
    void theStatementNamesWhatIsStoredTheRetentionAndTheImmediateErasureRoute() {
        String t = ConsentStatement.CURRENT.text();
        assertThat(t).contains("name and email", "Google", "risk profile", "target age", "7 days", "DELETE /me");
        assertThat(ConsentStatement.CURRENT.version()).isNotEqualTo("2026-10-v1");
    }

    @Test
    void serviceRequireThrowsWithoutConsentAndPassesWithIt() throws Exception {
        DpdpFixture f = new DpdpFixture();
        UUID u = UUID.randomUUID();
        DpdpFixture.insertUser(u, "U");
        DpdpFixture.actAs(u);
        assertThatThrownBy(() -> f.consents.require(u)).isInstanceOf(ConsentRequiredException.class);
        f.consents.grant(u, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
        f.consents.require(u);
        assertThat(f.consents.withdraw(u)).isTrue();
        assertThatThrownBy(() -> f.consents.require(u)).isInstanceOf(ConsentRequiredException.class);
    }

    @Test
    void grantIsIdempotentAndRefusesAStaleOrUnknownStatement() throws Exception {
        DpdpFixture f = new DpdpFixture();
        UUID u = UUID.randomUUID();
        DpdpFixture.insertUser(u, "U");
        DpdpFixture.actAs(u);
        var first = f.consents.grant(u, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
        var second = f.consents.grant(u, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
        assertThat(second).isEqualTo(first);
        assertThat(f.jdbc.queryForObject("SELECT count(*) FROM consent", Long.class)).isEqualTo(1L);
        assertThatThrownBy(() -> f.consents.grant(u, ConsentStatement.CURRENT.purpose(), "2000-01-v0"))
                .isInstanceOf(ConsentStatementMismatchException.class);
        assertThatThrownBy(() -> f.consents.grant(u, "OTHER", ConsentStatement.CURRENT.version()))
                .isInstanceOf(ConsentStatementMismatchException.class);
    }
}
