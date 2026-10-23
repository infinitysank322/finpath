package com.finpath.dpdp;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpath.db.TestDb;
import com.finpath.dpdp.DataSubjectRegistry.TableRef;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Export and deletion against a real database, under row-level security. Skipped without a scratch DB. */
class DataSubjectRightsDbTest {

    private DpdpFixture f;
    private UUID a;
    private UUID b;

    @BeforeEach
    void seed() throws SQLException {
        f = new DpdpFixture();   // wipes and re-migrates, so every test starts empty
        a = UUID.randomUUID();
        b = UUID.randomUUID();
        DpdpFixture.insertUser(a, "Alice Example");
        DpdpFixture.insertUser(b, "Bob Example");
        DpdpFixture.actAs(a);
        f.consents.grant(a, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
        DpdpFixture.actAs(b);
        f.consents.grant(b, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
        seedPlanCycle(a);
        seedPlanCycle(b);
        seedIncomeSource(a);
        seedIncomeSource(b);
        seedTransaction(a);
        seedTransaction(b);
        seedRecurringTemplate(a);
        seedRecurringTemplate(b);
        seedCategoryBudget(a);
        seedCategoryBudget(b);
        seedLoan(a);
        seedLoan(b);
        seedGoal(a);
        seedGoal(b);
    }

    /** One goal row per user (superuser insert). */
    private static void seedGoal(UUID user) throws SQLException {
        try (Connection c = TestDb.admin(); java.sql.PreparedStatement ps = c.prepareStatement(
                "INSERT INTO goal (user_id, type, target_amount_paise, target_date, current_amount_paise, priority) "
                        + "VALUES (?, 'other', 2000000, DATE '2030-01-01', 0, 3)")) {
            ps.setObject(1, user);
            ps.executeUpdate();
        }
    }

    /** One loan row per user (superuser insert). */
    private static void seedLoan(UUID user) throws SQLException {
        try (Connection c = TestDb.admin(); java.sql.PreparedStatement ps = c.prepareStatement(
                "INSERT INTO loan (user_id, lender, type, principal_outstanding_paise, rate, emi_paise, emis_left) "
                        + "VALUES (?, 'Test Lender', 'emi', 1000000, 0.08, 20000, 60)")) {
            ps.setObject(1, user);
            ps.executeUpdate();
        }
    }

    /** One category_budget row per user (superuser insert). */
    private static void seedCategoryBudget(UUID user) throws SQLException {
        try (Connection c = TestDb.admin(); java.sql.PreparedStatement ps = c.prepareStatement(
                "INSERT INTO category_budget (user_id, cycle_start, category_id, budget_paise) "
                        + "VALUES (?, DATE '2026-10-01', (SELECT id FROM category WHERE code = 'DINING'), 500000)")) {
            ps.setObject(1, user);
            ps.executeUpdate();
        }
    }

    /** One recurring_template row per user (superuser insert). */
    private static void seedRecurringTemplate(UUID user) throws SQLException {
        try (Connection c = TestDb.admin(); java.sql.PreparedStatement ps = c.prepareStatement(
                "INSERT INTO recurring_template (user_id, category_id, amount_paise, frequency, mode, start_date) "
                        + "VALUES (?, (SELECT id FROM category WHERE code = 'UTILITIES'), 600000, 'QUARTERLY', "
                        + "'UPI', DATE '2026-10-01')")) {
            ps.setObject(1, user);
            ps.executeUpdate();
        }
    }

    /** One transaction row per user (superuser insert). */
    private static void seedTransaction(UUID user) throws SQLException {
        try (Connection c = TestDb.admin(); java.sql.PreparedStatement ps = c.prepareStatement(
                "INSERT INTO transaction (user_id, txn_date, amount_paise, direction, category_id, mode, "
                        + "dedupe_hash, idempotency_key) VALUES (?, DATE '2026-10-01', 4000, 'OUT', "
                        + "(SELECT id FROM category WHERE code = 'DINING'), 'UPI', repeat('a', 64), 'seed-key-0001')")) {
            ps.setObject(1, user);
            ps.executeUpdate();
        }
    }

    /** One income_source row per user (superuser insert). */
    private static void seedIncomeSource(UUID user) throws SQLException {
        try (Connection c = TestDb.admin(); java.sql.PreparedStatement ps = c.prepareStatement(
                "INSERT INTO income_source (user_id, type, frequency, amount_paise, start_date) "
                        + "VALUES (?, 'SALARY', 'MONTHLY', 10000000, DATE '2026-01-01')")) {
            ps.setObject(1, user);
            ps.executeUpdate();
        }
    }

    /** One plan_cycle row per user (superuser insert), so the generic one-row-per-table checks cover it. */
    private static void seedPlanCycle(UUID user) throws SQLException {
        try (Connection c = TestDb.admin(); java.sql.PreparedStatement ps = c.prepareStatement(
                "INSERT INTO plan_cycle (user_id, cycle_start, cycle_end, start_day) "
                        + "VALUES (?, DATE '2026-09-25', DATE '2026-10-25', 25)")) {
            ps.setObject(1, user);
            ps.executeUpdate();
        }
    }

    private static List<String> allTables() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = TestDb.admin(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                        + "AND tablename <> 'flyway_schema_history' ORDER BY 1")) {
            while (rs.next()) {
                if (!DataSubjectRegistry.sharedTables().contains(rs.getString(1))) {
                    out.add(rs.getString(1));   // shared reference tables hold no user data
                }
            }
        }
        return out;
    }

    private static long rows(String table) throws SQLException {
        try (Connection c = TestDb.admin(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void exportIsValidJsonCoveringEveryRegisteredTableAndNoOneElsesRows() throws Exception {
        DpdpFixture.actAs(a);
        JsonNode export = f.data.export(a);
        JsonNode reparsed = new ObjectMapper().readTree(export.toString());   // valid JSON round trip

        assertThat(reparsed.get("format").asInt()).isEqualTo(1);
        assertThat(reparsed.get("exportedAt").asText()).isNotBlank();
        List<String> keys = new ArrayList<>();
        Iterator<String> names = reparsed.get("tables").fieldNames();
        names.forEachRemaining(keys::add);
        assertThat(keys).containsExactlyInAnyOrderElementsOf(
                DataSubjectRegistry.exportTables().stream().map(TableRef::table).toList());
        assertThat(keys).as("export covers every table in the database").containsExactlyInAnyOrderElementsOf(allTables());

        assertThat(reparsed.at("/tables/users")).hasSize(1);
        assertThat(reparsed.at("/tables/users/0/id").asText()).isEqualTo(a.toString());
        assertThat(reparsed.at("/tables/consent")).hasSize(1);
        assertThat(reparsed.at("/tables/consent/0/user_id").asText()).isEqualTo(a.toString());
        assertThat(reparsed.toString()).doesNotContain(b.toString());
        assertThat(reparsed.at("/tables/transaction")).hasSize(1);
        assertThat(reparsed.at("/tables/transaction/0/category_code").asText()).isEqualTo("DINING");
        assertThat(reparsed.at("/tables/transaction/0/amount_paise").asLong()).isEqualTo(4000);
        assertThat(reparsed.at("/tables/recurring_template")).hasSize(1);
        assertThat(reparsed.at("/tables/recurring_template/0/category_code").asText()).isEqualTo("UTILITIES");
        assertThat(reparsed.at("/tables/recurring_template/0/amount_paise").asLong()).isEqualTo(600000);
        assertThat(reparsed.at("/tables/category_budget")).hasSize(1);
        assertThat(reparsed.at("/tables/category_budget/0/category_code").asText()).isEqualTo("DINING");
        assertThat(reparsed.at("/tables/category_budget/0/budget_paise").asLong()).isEqualTo(500000);
        assertThat(reparsed.at("/tables/loan")).hasSize(1);
        assertThat(reparsed.at("/tables/loan/0/principal_outstanding_paise").asLong()).isEqualTo(1000000);
        assertThat(reparsed.at("/tables/goal")).hasSize(1);
        assertThat(reparsed.at("/tables/goal/0/target_amount_paise").asLong()).isEqualTo(2000000);
        assertThat(reparsed.get("tables").has("category")).isFalse();
    }

    @Test
    void exportOfAUserWithNoRowsIsEmptyArraysNotMissingKeys() throws Exception {
        UUID c = UUID.randomUUID();
        DpdpFixture.actAs(c);   // no users row: RLS and the WHERE clause both yield nothing
        JsonNode tables = f.data.export(c).get("tables");
        for (TableRef t : DataSubjectRegistry.exportTables()) {
            assertThat(tables.get(t.table()).isArray()).isTrue();
            assertThat(tables.get(t.table())).isEmpty();
        }
    }

    @Test
    void afterDeleteEveryTableHoldsNoRowsOfTheUserAndOthersAreUntouched() throws Exception {
        DpdpFixture.actAs(a);
        f.data.delete(a);
        for (String table : allTables()) {
            assertThat(rows(table)).as("rows left in %s for another user only", table).isEqualTo(1);
        }
        try (Connection c = TestDb.admin(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT (SELECT count(*) FROM users WHERE id = '" + a + "') "
                        + "+ (SELECT count(*) FROM consent WHERE user_id = '" + a + "') "
                        + "+ (SELECT count(*) FROM plan_cycle WHERE user_id = '" + a + "') "
                        + "+ (SELECT count(*) FROM income_source WHERE user_id = '" + a + "') "
                        + "+ (SELECT count(*) FROM transaction WHERE user_id = '" + a + "') "
                        + "+ (SELECT count(*) FROM recurring_template WHERE user_id = '" + a + "') "
                        + "+ (SELECT count(*) FROM category_budget WHERE user_id = '" + a + "')")) {
            rs.next();
            assertThat(rs.getLong(1)).isZero();
        }
    }

    @Test
    void afterDeletingEveryoneEveryTableReturnsZeroRows() throws Exception {
        DpdpFixture.actAs(a);
        f.data.delete(a);
        DpdpFixture.actAs(b);
        f.data.delete(b);
        for (String table : allTables()) {
            assertThat(rows(table)).as("rows left in table %s", table).isZero();
        }
    }

    @Test
    void deleteCannotTouchAnotherUsersRowsEvenIfAskedFor() throws Exception {
        DpdpFixture.actAs(b);   // principal is B ...
        f.data.delete(a);       // ... but the call names A: RLS makes it a no-op
        for (String table : allTables()) {
            assertThat(rows(table)).as("rows in %s", table).isEqualTo(2);
        }
    }

    @Test
    void deleteWorksWithConsentRowsPresentBecauseChildrenGoFirst() throws Exception {
        DpdpFixture.actAs(a);
        f.data.delete(a);
        assertThat(rows("category")).as("shared taxonomy is never deleted").isPositive();
        assertThat(rows("consent")).isEqualTo(1);
        assertThat(rows("users")).isEqualTo(1);
    }
}
