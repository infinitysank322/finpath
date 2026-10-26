package com.finpath.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * A new table without a row-level-security policy fails here by name. The static half reads the
 * migrations and runs on every build (CI has no database); the database half checks the migrated
 * catalog and runs wherever a scratch database is configured.
 */
class RlsMetadataTest {

    /** SECURITY DEFINER functions are RLS bypasses by design; adding one needs a conscious edit here. */
    static final Set<String> SECURITY_DEFINER_ALLOW_LIST = Set.of("finpath_user_id_by_email",
            "finpath_purge_unconsented_users", "finpath_reminder_users");

    @Test
    void everyMigratedTableEnablesForcesAndHasAPolicy() throws IOException {
        String code = MigrationScan.allCode().toLowerCase();
        List<String> failures = new ArrayList<>();
        for (MigrationScan.Table t : MigrationScan.tables(code)) {
            if (com.finpath.dpdp.DataSubjectRegistry.sharedTables().contains(t.name())) {
                continue;   // reviewed exemption: shared reference data, no personal data
            }
            String n = Pattern.quote(t.name());
            if (!Pattern.compile("alter\\s+table\\s+" + n + "\\s+enable\\s+row\\s+level\\s+security").matcher(code).find()) {
                failures.add(t.name() + ": ENABLE ROW LEVEL SECURITY missing");
            }
            if (!Pattern.compile("alter\\s+table\\s+" + n + "\\s+force\\s+row\\s+level\\s+security").matcher(code).find()) {
                failures.add(t.name() + ": FORCE ROW LEVEL SECURITY missing");
            }
            if (!Pattern.compile("create\\s+policy\\s+\\w+\\s+on\\s+" + n + "\\b").matcher(code).find()) {
                failures.add(t.name() + ": no CREATE POLICY");
            }
        }
        assertThat(failures).as("tables without row-level security").isEmpty();
    }

    @Test
    void securityDefinerFunctionsAreOnTheAllowList() throws IOException {
        // Each CREATE FUNCTION's region runs to the next CREATE FUNCTION (or the end), so a ';' inside
        // a body cannot hide a later SECURITY DEFINER attribute.
        String code = MigrationScan.allCode().toLowerCase();
        List<String> found = securityDefinerFunctions(code);
        assertThat(found).as("SECURITY DEFINER functions in migrations").isSubsetOf(SECURITY_DEFINER_ALLOW_LIST);
    }

    static List<String> securityDefinerFunctions(String code) {
        Matcher m = Pattern.compile("create\\s+(?:or\\s+replace\\s+)?function\\s+([a-z_][a-z0-9_]*)").matcher(code);
        List<String> starts = new ArrayList<>();
        List<Integer> at = new ArrayList<>();
        while (m.find()) {
            starts.add(m.group(1));
            at.add(m.start());
        }
        List<String> found = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            int end = i + 1 < at.size() ? at.get(i + 1) : code.length();
            if (Pattern.compile("security\\s+definer").matcher(code.substring(at.get(i), end)).find()) {
                found.add(starts.get(i));
            }
        }
        return found;
    }

    @Test
    void detectorSeesSecurityDefinerAfterASemicolonInTheBody() {
        String sql = "create function sneaky() returns int as $$ select 1; select 2; $$ language sql security definer;";
        assertThat(securityDefinerFunctions(sql)).containsExactly("sneaky");
        assertThat(securityDefinerFunctions("create function ok() returns int as $$ select 1 $$ language sql;"))
                .isEmpty();
    }

    @Nested
    class AgainstDatabase {

        AgainstDatabase() {
            TestDb.requireAndMigrate();
        }

        private List<String> column(String sql) throws SQLException {
            List<String> out = new ArrayList<>();
            try (Connection c = TestDb.admin(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
            return out;
        }

        @Test
        void everyTableIsForcedAndHasAPolicy() throws SQLException {
            assertThat(column("SELECT c.relname FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                    + "WHERE n.nspname = 'public' AND c.relkind IN ('r','p') AND c.relname <> 'flyway_schema_history' "
                    + "AND c.relname NOT IN ('" + String.join("','", com.finpath.dpdp.DataSubjectRegistry.sharedTables()) + "') "
                    + "AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity "
                    + "OR NOT EXISTS (SELECT 1 FROM pg_policy p WHERE p.polrelid = c.oid))"))
                    .as("tables lacking forced RLS or a policy").isEmpty();
        }

        @Test
        void securityDefinerFunctionsInTheDatabaseAreOnTheAllowList() throws SQLException {
            assertThat(column("SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                    + "WHERE n.nspname = 'public' AND p.prosecdef")).isSubsetOf(SECURITY_DEFINER_ALLOW_LIST);
        }

        @Test
        void appUserIsBoundByRowLevelSecurity() throws SQLException {
            assertThat(column("SELECT rolname FROM pg_roles WHERE rolname = 'app_user' "
                    + "AND (rolsuper OR rolbypassrls OR rolcanlogin)")).isEmpty();
            assertThat(column("SELECT relname FROM pg_class c JOIN pg_roles r ON r.oid = c.relowner "
                    + "WHERE r.rolname = 'app_user' AND c.relkind IN ('r','p')")).isEmpty();
        }
    }
}
