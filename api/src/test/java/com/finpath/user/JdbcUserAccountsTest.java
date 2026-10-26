package com.finpath.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sign-in upsert into V2 users, through the V2.1 lookup function. The database part runs against
 * FINPATH_TEST_DB_URL like UsersRlsTest (a superuser on a scratch database: migrates, never
 * cleans, removes only the rows it creates), and runs the upsert itself as {@code SET ROLE app_user}
 * so row-level security applies. Reported as skipped when no database is configured.
 */
class JdbcUserAccountsTest {

    private static final String URL = System.getenv("FINPATH_TEST_DB_URL");
    private static final String USER = System.getenv().getOrDefault("FINPATH_TEST_DB_USER", "postgres");
    private static final String PASSWORD = System.getenv().getOrDefault("FINPATH_TEST_DB_PASSWORD", "");

    @Test
    void displayNameFallsBackToEmailLocalPartAndIsCapped() {
        assertThat(JdbcUserAccounts.nameOrDefault("  Asha K ", "a@example.com")).isEqualTo("Asha K");
        assertThat(JdbcUserAccounts.nameOrDefault(null, "asha.k@example.com")).isEqualTo("asha.k");
        assertThat(JdbcUserAccounts.nameOrDefault(" ", "@example.com")).isEqualTo("FinPath user");
        assertThat(JdbcUserAccounts.nameOrDefault("x".repeat(500), "a@example.com")).hasSize(200);
    }

    @Test
    void withoutADatasourceSignInFailsClearlyInsteadOfAtStartup() {
        StaticListableBeanFactory empty = new StaticListableBeanFactory();
        JdbcUserAccounts accounts = new JdbcUserAccounts(empty.getBeanProvider(JdbcTemplate.class),
                empty.getBeanProvider(TransactionTemplate.class));
        assertThatThrownBy(() -> accounts.findOrCreateByVerifiedEmail("a@example.com", "A"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("datasource");
    }

    /** Connections that run as the RLS-bound application role, like the app after T30. */
    static final class AppUserDataSource extends DriverManagerDataSource {
        AppUserDataSource() {
            super(URL, USER, PASSWORD);
        }

        @Override
        protected Connection getConnectionFromDriver(String username, String password) throws SQLException {
            Connection c = super.getConnectionFromDriver(username, password);
            try (Statement s = c.createStatement()) {
                s.execute("SET ROLE app_user");
            }
            return c;
        }
    }

    @Nested
    class AgainstDatabase {

        private final JdbcTemplate admin;
        private final JdbcTemplate app;
        private final JdbcUserAccounts accounts;

        AgainstDatabase() {
            assumeTrue(URL != null, "SKIPPED: FINPATH_TEST_DB_URL not set, upsert not exercised against a database");
            assumeTrue(URL.toLowerCase().matches(".*/[^/?]*(test|scratch)[^/?]*(\\?.*)?$"),
                    "SKIPPED: refusing to write to a database whose name lacks 'test' or 'scratch'");
            DriverManagerDataSource superuser = new DriverManagerDataSource(URL, USER, PASSWORD);
            Flyway.configure().dataSource(superuser).load().migrate();
            admin = new JdbcTemplate(superuser);
            AppUserDataSource appDs = new AppUserDataSource();
            app = new JdbcTemplate(appDs);
            accounts = new JdbcUserAccounts(app, new TransactionTemplate(new com.finpath.rls.RlsTransactionManager(appDs)));
        }

        private String email() {
            return "signin-" + UUID.randomUUID() + "@example.test";
        }

        @Test
        void firstAndReturningSignInAsAppUserResolveToTheSameRow() {
            String email = email();
            try {
                UUID first = accounts.findOrCreateByVerifiedEmail(email, "First Name");
                UUID again = accounts.findOrCreateByVerifiedEmail(email, "Changed Name");
                assertThat(again).isEqualTo(first);
                assertThat(admin.queryForObject("SELECT count(*) FROM users WHERE email = ?", Long.class, email))
                        .isEqualTo(1L);
                assertThat(admin.queryForObject("SELECT name FROM users WHERE id = ?", String.class, first))
                        .isEqualTo("First Name");
                assertThat(admin.queryForObject("SELECT cycle_start_day FROM users WHERE id = ?", Short.class,
                        first)).isEqualTo(User.DEFAULT_CYCLE_START_DAY);
                // The policy still hides the row from app_user outside the sign-in function.
                assertThat(app.queryForObject("SELECT count(*) FROM users WHERE email = ?", Long.class, email))
                        .isZero();
            } finally {
                admin.update("DELETE FROM users WHERE email = ?", email);
            }
        }

        @Test
        void lookupFunctionReturnsOnlyAnIdForAnExactEmail() {
            String email = email();
            try {
                UUID id = accounts.findOrCreateByVerifiedEmail(email, "A");
                assertThat(app.queryForObject("SELECT finpath_user_id_by_email(?)", UUID.class, email)).isEqualTo(id);
                assertThat(app.queryForObject("SELECT finpath_user_id_by_email(?)", UUID.class,
                        email.toUpperCase())).isEqualTo(id);
                // No pattern matching, so no enumeration.
                assertThat(app.queryForObject("SELECT finpath_user_id_by_email('%')", UUID.class)).isNull();
                assertThat(app.queryForObject("SELECT finpath_user_id_by_email('%example.test')", UUID.class))
                        .isNull();
            } finally {
                admin.update("DELETE FROM users WHERE email = ?", email);
            }
        }

        @Test
        void lookupFunctionIsScalarDefinerPinnedAndNotPublic() {
            var fn = admin.queryForMap("""
                    SELECT p.proretset, p.prorettype::regtype::text AS rettype, p.prosecdef,
                           array_to_string(p.proconfig, ',') AS config, pg_get_userbyid(p.proowner) AS owner,
                           array_to_string(p.proacl, ',') AS acl
                    FROM pg_proc p WHERE p.proname = 'finpath_user_id_by_email'""");
            assertThat(fn.get("proretset")).isEqualTo(false);
            assertThat(fn.get("rettype")).isEqualTo("uuid");
            assertThat(fn.get("prosecdef")).isEqualTo(true);
            assertThat((String) fn.get("config")).contains("search_path=pg_catalog, pg_temp");
            assertThat(fn.get("owner")).isEqualTo("finpath_signin");
            assertThat((String) fn.get("acl")).doesNotStartWith("=X").doesNotContain(",=X").contains("app_user=X");
            assertThat(admin.queryForObject(
                    "SELECT rolcanlogin FROM pg_roles WHERE rolname = 'finpath_signin'", Boolean.class)).isFalse();
        }

        /** SET ROLE is checked against the session user (superuser here), so check membership itself. */
        @Test
        void appUserCannotAssumeTheSignInRoleAndTheRoleKeepsNoSchemaCreate() {
            assertThat(admin.queryForObject("SELECT pg_has_role('app_user', 'finpath_signin', 'MEMBER')",
                    Boolean.class)).isFalse();
            assertThat(admin.queryForObject("SELECT has_schema_privilege('finpath_signin', 'public', 'CREATE')",
                    Boolean.class)).isFalse();
            assertThat(admin.queryForObject(
                    "SELECT has_column_privilege('finpath_signin', 'users', 'name', 'SELECT')", Boolean.class))
                    .isFalse();
        }


        // ---- Migrations run by a non-superuser, non-BYPASSRLS owner (the prod shape, e.g. Neon) ----

        /** Body of a scratch-owner scenario. */
        interface OwnerScenario {
            void run(String role, String rolePassword, String dbUrl) throws Exception;
        }

        /**
         * Creates a throwaway LOGIN role (NOSUPERUSER NOBYPASSRLS CREATEROLE) and a database it owns,
         * optionally with {@code createrole_self_grant} set for it, and runs the scenario. Roles are
         * cluster-wide, so an existing finpath_signin (from this cluster's main test database) is
         * renamed out of the way for the duration: the scratch owner then really creates the role
         * itself, which is what triggers PostgreSQL 16+'s implicit creator grant. Everything is
         * dropped or restored afterwards.
         */
        private void withScratchOwner(String createroleSelfGrant, OwnerScenario scenario) throws Exception {
            assumeTrue(Boolean.TRUE.equals(admin.queryForObject(
                    "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class)),
                    "SKIPPED: the test database user cannot create a scratch owner role and database");
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            String role = "finpath_t29_owner_" + suffix;
            String db = "finpath_scratch_owner_" + suffix;
            String parked = "finpath_signin_parked_" + suffix;
            String rolePassword = UUID.randomUUID().toString();
            String dbUrl = URL.replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
            boolean hadSignin = roleExists("finpath_signin");
            if (hadSignin) {
                admin.execute("ALTER ROLE finpath_signin RENAME TO " + parked);
            }
            // V3 (T31) adds a second cluster-wide role with the same lifecycle.
            String parkedPurge = "finpath_purge_parked_" + suffix;
            boolean hadPurge = roleExists("finpath_purge");
            if (hadPurge) {
                admin.execute("ALTER ROLE finpath_purge RENAME TO " + parkedPurge);
            }
            // V15 (T72) adds a third one for the reminder-user listing.
            String parkedReminder = "finpath_reminder_parked_" + suffix;
            boolean hadReminder = roleExists("finpath_reminder");
            if (hadReminder) {
                admin.execute("ALTER ROLE finpath_reminder RENAME TO " + parkedReminder);
            }
            try {
                admin.execute("CREATE ROLE " + role + " LOGIN NOSUPERUSER NOBYPASSRLS CREATEROLE PASSWORD '"
                        + rolePassword + "'");
                if (createroleSelfGrant != null) {
                    admin.execute("ALTER ROLE " + role + " SET createrole_self_grant = '" + createroleSelfGrant + "'");
                }
                admin.execute("CREATE DATABASE " + db + " OWNER " + role);
                scenario.run(role, rolePassword, dbUrl);
            } finally {
                admin.execute("DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
                admin.execute("DROP ROLE IF EXISTS finpath_signin"); // only ever the scratch owner's copy here
                admin.execute("DROP ROLE IF EXISTS finpath_purge");
                admin.execute("DROP ROLE IF EXISTS finpath_reminder");
                admin.execute("DROP ROLE IF EXISTS " + role);
                if (hadSignin) {
                    admin.execute("ALTER ROLE " + parked + " RENAME TO finpath_signin");
                }
                if (hadPurge) {
                    admin.execute("ALTER ROLE " + parkedPurge + " RENAME TO finpath_purge");
                }
                if (hadReminder) {
                    admin.execute("ALTER ROLE " + parkedReminder + " RENAME TO finpath_reminder");
                }
            }
        }

        private boolean roleExists(String name) {
            return Boolean.TRUE.equals(admin.queryForObject(
                    "SELECT count(*) > 0 FROM pg_roles WHERE rolname = ?", Boolean.class, name));
        }

        private boolean hasRole(String member, String privilege) {
            return Boolean.TRUE.equals(admin.queryForObject(
                    "SELECT pg_has_role(?, 'finpath_signin', ?)", Boolean.class, member, privilege));
        }

        private boolean hasPurgeRole(String member, String privilege) {
            return Boolean.TRUE.equals(admin.queryForObject(
                    "SELECT pg_has_role(?, 'finpath_purge', ?)", Boolean.class, member, privilege));
        }

        private boolean pg16OrLater() {
            return admin.queryForObject("SELECT current_setting('server_version_num')::int", Integer.class) >= 160000;
        }

        /**
         * The owner creates finpath_signin itself and migrates, with createrole_self_grant unset or
         * set to make that creation hand the owner an inheriting and/or SET-able membership. In every
         * case the migration removes that membership: the owner ends with no usable membership,
         * sign-in works both as that owner (the app's role until T30) and as app_user, and FORCE
         * RLS still hides every row from the owner's direct reads.
         */
        @ParameterizedTest(name = "createrole_self_grant = {0}")
        @NullSource
        @ValueSource(strings = {"inherit", "set", "inherit, set"})
        void signInWorksWhenTheOwnerHasNeitherSuperuserNorBypassRls(String selfGrant) throws Exception {
            assumeTrue(selfGrant == null || pg16OrLater(), "SKIPPED: createrole_self_grant exists from PostgreSQL 16");
            withScratchOwner(selfGrant, (role, rolePassword, dbUrl) -> {
                if (selfGrant != null) {
                    // Prove the setting is live for the owner's sessions, so this case is really exercised.
                    assertThat(new JdbcTemplate(new DriverManagerDataSource(dbUrl, role, rolePassword))
                            .queryForObject("SHOW createrole_self_grant", String.class)).isEqualTo(selfGrant);
                }
                DriverManagerDataSource ownerDs = new DriverManagerDataSource(dbUrl, role, rolePassword);
                Flyway.configure().dataSource(ownerDs).load().migrate();

                JdbcTemplate owner = new JdbcTemplate(ownerDs);
                assertThat(owner.queryForObject(
                        "SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = current_user", Boolean.class))
                        .isFalse();
                assertThat(roleExists("finpath_signin")).as("created by the scratch owner").isTrue();
                assertThat(hasRole(role, "USAGE")).as("owner must not inherit finpath_signin").isFalse();
                if (pg16OrLater()) {
                    assertThat(hasRole(role, "SET")).as("owner must not SET ROLE finpath_signin").isFalse();
                }
                assertThat(roleExists("finpath_purge")).as("V3 role created by the scratch owner").isTrue();
                assertThat(hasPurgeRole(role, "USAGE")).as("owner must not inherit finpath_purge").isFalse();
                if (pg16OrLater()) {
                    assertThat(hasPurgeRole(role, "SET")).as("owner must not SET ROLE finpath_purge").isFalse();
                }
                // The purge works for the owner (the app's login role), as a non-superuser.
                assertThat(owner.queryForObject("SELECT cardinality(finpath_purge_unconsented_users(interval '1 day'))",
                        Integer.class)).isNotNull();

                JdbcUserAccounts asOwner = new JdbcUserAccounts(owner,
                        new TransactionTemplate(new DataSourceTransactionManager(ownerDs)));
                String ownerEmail = email();
                UUID ownerFirst = asOwner.findOrCreateByVerifiedEmail(ownerEmail, "Owner Path");
                assertThat(asOwner.findOrCreateByVerifiedEmail(ownerEmail, "Owner Path")).isEqualTo(ownerFirst);
                // FORCE RLS really binds this owner: a direct read sees nothing.
                assertThat(owner.queryForObject("SELECT count(*) FROM users", Long.class)).isZero();

                DriverManagerDataSource appDs = new DriverManagerDataSource(dbUrl, USER, PASSWORD) {
                    @Override
                    protected Connection getConnectionFromDriver(String u, String p) throws SQLException {
                        Connection c = super.getConnectionFromDriver(u, p);
                        try (Statement s = c.createStatement()) {
                            s.execute("SET ROLE app_user");
                        }
                        return c;
                    }
                };
                JdbcUserAccounts asApp = new JdbcUserAccounts(new JdbcTemplate(appDs),
                        new TransactionTemplate(new com.finpath.rls.RlsTransactionManager(appDs)));
                String appEmail = email();
                UUID appFirst = asApp.findOrCreateByVerifiedEmail(appEmail, "App Path");
                assertThat(asApp.findOrCreateByVerifiedEmail(appEmail, "App Path")).isEqualTo(appFirst);
                assertThat(asApp.findOrCreateByVerifiedEmail(ownerEmail, "x")).isEqualTo(ownerFirst);
            });
        }

        /** The same fail-closed guarantee for V3's finpath_purge, which can delete any users row. */
        @ParameterizedTest(name = "out-of-band finpath_purge grant WITH {0}")
        @ValueSource(strings = {"INHERIT TRUE, SET FALSE", "INHERIT FALSE, SET TRUE"})
        void v3MigrationFailsClosedWhenTheOwnerWouldKeepThePurgeRole(String flags) throws Exception {
            assumeTrue(pg16OrLater(), "SKIPPED: membership INHERIT/SET options exist from PostgreSQL 16");
            withScratchOwner(null, (role, rolePassword, dbUrl) -> {
                admin.execute("CREATE ROLE finpath_purge NOLOGIN NOINHERIT");
                admin.execute("GRANT finpath_purge TO " + role + " WITH ADMIN TRUE, " + flags);
                DriverManagerDataSource ownerDs = new DriverManagerDataSource(dbUrl, role, rolePassword);
                assertThatThrownBy(() -> Flyway.configure().dataSource(ownerDs).load().migrate())
                        .isInstanceOf(FlywayException.class)
                        .hasStackTraceContaining("V3 refused");
                JdbcTemplate superuserOnScratch = new JdbcTemplate(new DriverManagerDataSource(dbUrl, USER, PASSWORD));
                assertThat(superuserOnScratch.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '3' AND success", Long.class))
                        .isZero();
                assertThat(superuserOnScratch.queryForObject(
                        "SELECT count(*) FROM pg_policies WHERE policyname = 'users_purge_delete'", Long.class))
                        .as("policy rolled back with the migration").isZero();
            });
        }

        /**
         * A membership the owner cannot revoke (granted by another role, here the superuser, WITH
         * INHERIT or SET) would leave it reading every users row. The migration must fail loudly and
         * roll back, never finish in that state: afterwards V2.1 is not recorded, the lookup policy
         * does not exist, and the owner still cannot read a seeded row.
         */
        @ParameterizedTest(name = "out-of-band grant WITH {0}")
        @ValueSource(strings = {"INHERIT TRUE, SET FALSE", "INHERIT FALSE, SET TRUE"})
        void migrationFailsClosedWhenTheOwnerWouldKeepTheSignInRole(String flags) throws Exception {
            assumeTrue(pg16OrLater(), "SKIPPED: membership INHERIT/SET options exist from PostgreSQL 16");
            withScratchOwner(null, (role, rolePassword, dbUrl) -> {
                admin.execute("CREATE ROLE finpath_signin NOLOGIN NOINHERIT");
                admin.execute("GRANT finpath_signin TO " + role + " WITH ADMIN TRUE, " + flags);
                DriverManagerDataSource ownerDs = new DriverManagerDataSource(dbUrl, role, rolePassword);
                assertThatThrownBy(() -> Flyway.configure().dataSource(ownerDs).load().migrate())
                        .isInstanceOf(FlywayException.class)
                        .hasStackTraceContaining("V2_1 refused");

                JdbcTemplate superuserOnScratch = new JdbcTemplate(new DriverManagerDataSource(dbUrl, USER, PASSWORD));
                assertThat(superuserOnScratch.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '2.1' AND success", Long.class))
                        .isZero();
                assertThat(superuserOnScratch.queryForObject(
                        "SELECT count(*) FROM pg_policies WHERE policyname = 'users_signin_lookup'", Long.class))
                        .as("policy rolled back with the migration").isZero();
                superuserOnScratch.update("INSERT INTO users (name, email) VALUES ('Seed', ?)", email());
                assertThat(new JdbcTemplate(ownerDs).queryForObject("SELECT count(*) FROM users", Long.class))
                        .as("owner still bound by FORCE RLS").isZero();
            });
        }
    }
}
