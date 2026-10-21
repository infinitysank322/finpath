package com.finpath.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Static checks on the migrations: identity-shaped columns are banned, every table has RLS. */
class UsersMigrationTest {

    /** Account, card and government-id shaped column names. Never stored (CLAUDE.md). */
    static final Pattern IDENTITY_COLUMN = Pattern.compile(
            "(?i).*(account_?(no|num|number)|acct|card_?(no|num|number)|aadhaar|aadhar|\\bpan\\b|^pan$|pan_?(no|num|number)"
                    + "|passport|voter|driving_?licen|ssn|iban|ifsc|folio|demat|cvv).*");

    @Test
    void usersTableHasTheSpecifiedColumnsAndNoIdentityColumn() throws IOException {
        var users = MigrationScan.tables(MigrationScan.allCode()).stream()
                .filter(t -> t.name().equals("users")).findFirst().orElseThrow();
        assertThat(users.columns()).contains(
                "id", "name", "email", "cycle_start_day", "currency", "risk_profile", "fi_target_age");
        assertThat(users.columns()).noneMatch(c -> IDENTITY_COLUMN.matcher(c).matches());
    }

    @Test
    void noTableAnywhereHasAnIdentityNumberShapedColumn() throws IOException {
        for (var t : MigrationScan.tables(MigrationScan.allCode())) {
            assertThat(t.columns()).as("columns of %s", t.name())
                    .noneMatch(c -> IDENTITY_COLUMN.matcher(c).matches());
        }
    }

    @Test
    void scannerSeesEveryCreateTableStatement() throws IOException {
        String code = MigrationScan.allCode();
        long statements = java.util.regex.Pattern.compile("(?i)\\bcreate\\s+table\\b").matcher(code).results().count();
        assertThat((long) MigrationScan.tables(code).size())
                .as("a CREATE TABLE layout MigrationScan cannot parse would skip the RLS and identity checks")
                .isEqualTo(statements);
    }

    @Test
    void identityLintFlagsTheBannedShapes() {
        for (String c : List.of("account_number", "card_no", "aadhaar_id", "pan", "pan_number", "folio_no",
                "demat_id", "ifsc_code", "passport_no")) {
            assertThat(IDENTITY_COLUMN.matcher(c).matches()).as(c).isTrue();
        }
        for (String c : List.of("name", "email", "currency", "cycle_start_day", "company", "span_days")) {
            assertThat(IDENTITY_COLUMN.matcher(c).matches()).as(c).isFalse();
        }
    }

    @Test
    void everyTableEnablesForcesAndPoliciesRowLevelSecurity() throws IOException {
        String code = MigrationScan.allCode().toLowerCase();
        for (var t : MigrationScan.tables(MigrationScan.allCode())) {
            assertThat(code).as("RLS enabled on %s", t.name())
                    .containsPattern("alter\\s+table\\s+" + t.name() + "\\s+enable\\s+row\\s+level\\s+security");
            assertThat(code).as("RLS forced on %s", t.name())
                    .containsPattern("alter\\s+table\\s+" + t.name() + "\\s+force\\s+row\\s+level\\s+security");
            assertThat(code).as("policy on %s", t.name())
                    .containsPattern("create\\s+policy\\s+\\w+\\s+on\\s+" + t.name() + "\\b");
        }
    }

    @Test
    void cycleStartDayDefaultsTo25() throws IOException {
        assertThat(MigrationScan.allCode()).containsPattern("(?i)cycle_start_day\\s+smallint\\s+not\\s+null\\s+default\\s+25");
    }
}
