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
    }

    private static List<String> allTables() throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = TestDb.admin(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT tablename FROM pg_tables WHERE schemaname = 'public' "
                        + "AND tablename <> 'flyway_schema_history' ORDER BY 1")) {
            while (rs.next()) {
                out.add(rs.getString(1));
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
                        + "+ (SELECT count(*) FROM consent WHERE user_id = '" + a + "')")) {
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
        assertThat(rows("consent")).isEqualTo(1);
        assertThat(rows("users")).isEqualTo(1);
    }
}
