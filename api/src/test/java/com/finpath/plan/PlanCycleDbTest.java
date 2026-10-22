package com.finpath.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpath.db.TestDb;
import com.finpath.dpdp.ConsentRequiredException;
import com.finpath.dpdp.ConsentService;
import com.finpath.dpdp.ConsentStatement;
import com.finpath.plan.PlanCycleService.PlanCycleView;
import com.finpath.rls.RlsTransactionManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Materialisation and start-day change against a real database under RLS (needs the scratch DB). */
class PlanCycleDbTest {

    private static volatile UUID current;

    /** Settable clock; the zone is irrelevant because the service reads the date in its own zone. */
    static final class MutableClock extends Clock {
        private Instant now;

        void set(LocalDate date) {
            now = date.atTime(12, 0).atZone(PlanCycleService.CYCLE_ZONE).toInstant();
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private PlanCycleService service;
    private ConsentService consents;
    private UUID user;

    @BeforeEach
    void setUp() throws SQLException {
        TestDb.requireAndMigrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(TestDb.URL, TestDb.USER, TestDb.PASSWORD);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(
                new RlsTransactionManager(ds, () -> Optional.ofNullable(current)));
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("jdbc", jdbc);
        bf.registerSingleton("tx", tx);
        consents = new ConsentService(bf.getBeanProvider(JdbcTemplate.class), bf.getBeanProvider(TransactionTemplate.class));
        service = new PlanCycleService(() -> jdbc, () -> tx, consents, new ObjectMapper(), clock);
        user = UUID.randomUUID();
        insertUser(user);
        current = user;
        clock.set(LocalDate.of(2026, 10, 22));
    }

    private void grant() {
        consents.grant(user, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
    }

    private static void insertUser(UUID id) throws SQLException {
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, name, email) VALUES (?, 'T', ?)")) {
            ps.setObject(1, id);
            ps.setString(2, id + "@example.test");
            ps.executeUpdate();
        }
    }

    private List<String> rows(UUID id) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "SELECT cycle_start || '/' || cycle_end || '/' || status FROM plan_cycle WHERE user_id = ? "
                        + "ORDER BY cycle_start")) {
            ps.setObject(1, id);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private void admin(String sql) throws SQLException {
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    @Test
    void materialisesTheCurrentCycleFromTheUsersStartDay() throws SQLException {
        grant();
        PlanCycleView v = service.current(user);
        assertThat(v.cycleStart()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(v.cycleEndExclusive()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(v.startDay()).isEqualTo(25);
        assertThat(v.status()).isEqualTo("OPEN");
        assertThat(v.surplusPaise()).isNull();
        assertThat(v.allocations().isArray()).isTrue();
        assertThat(v.daysElapsed()).isEqualTo(27);
        assertThat(v.daysLeft()).isEqualTo(3);
        assertThat(rows(user)).containsExactly("2026-09-25/2026-10-25/OPEN");
    }

    @Test
    void materialisationIsIdempotent() throws SQLException {
        grant();
        PlanCycleView first = service.current(user);
        PlanCycleView second = service.current(user);
        assertThat(second).isEqualTo(first);
        assertThat(rows(user)).hasSize(1);
    }

    @Test
    void rollingIntoTheNextCycleClosesTheElapsedOneAndOpensExactlyOne() throws SQLException {
        grant();
        service.current(user);
        clock.set(LocalDate.of(2026, 10, 25));
        PlanCycleView v = service.current(user);
        assertThat(v.cycleStart()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(rows(user)).containsExactly("2026-09-25/2026-10-25/CLOSED", "2026-10-25/2026-11-25/OPEN");
    }

    @Test
    void changingTheStartDayFrom25To1RederivesWithoutOrphaning() throws SQLException {
        grant();
        service.current(user);

        PlanCycleView v = service.changeStartDay(user, 1);

        assertThat(v.cycleStart()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(v.cycleEndExclusive()).isEqualTo(LocalDate.of(2026, 11, 1));
        assertThat(v.startDay()).isEqualTo(1);
        assertThat(v.status()).isEqualTo("OPEN");
        assertThat(rows(user)).containsExactly("2026-09-25/2026-10-25/SUPERSEDED", "2026-10-01/2026-11-01/OPEN");
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT cycle_start_day, (SELECT count(*) FROM plan_cycle "
                    + "WHERE user_id = '" + user + "' AND status = 'OPEN') FROM users WHERE id = '" + user + "'");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(1);
            assertThat(rs.getInt(2)).as("exactly one current cycle row").isEqualTo(1);
        }
        // Reading afterwards derives the same cycle and adds nothing.
        assertThat(service.current(user)).isEqualTo(v);
        assertThat(rows(user)).hasSize(2);
    }

    @Test
    void changingBackRevivesTheOriginalRowInsteadOfDuplicatingIt() throws SQLException {
        grant();
        service.current(user);
        service.changeStartDay(user, 1);
        PlanCycleView back = service.changeStartDay(user, 25);
        assertThat(back.cycleStart()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(rows(user)).containsExactly("2026-09-25/2026-10-25/OPEN", "2026-10-01/2026-11-01/SUPERSEDED");
    }

    @Test
    void changingTheDayWithAnUnchangedStartKeepsTheRowButResetsStaleAllocationsWhenTheEndMoves() throws SQLException {
        grant();
        clock.set(LocalDate.of(2027, 2, 28));
        service.changeStartDay(user, 30);   // Feb cycle starts on the 28th, ends 30 Mar
        admin("UPDATE plan_cycle SET surplus = 3983500, allocations = '[{\"step\":1}]' WHERE user_id = '" + user + "'");

        // Day 30 -> 30 again: same bounds, allocations survive.
        PlanCycleView same = service.changeStartDay(user, 30);
        assertThat(same.surplusPaise()).isEqualTo(3983500L);

        // Day 31: same start (clamped to 28 Feb), later end; the stored figures no longer fit.
        PlanCycleView moved = service.changeStartDay(user, 31);
        assertThat(moved.cycleStart()).isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(moved.cycleEndExclusive()).isEqualTo(LocalDate.of(2027, 3, 31));
        assertThat(moved.surplusPaise()).isNull();
        assertThat(moved.allocations()).isEmpty();
        assertThat(rows(user)).containsExactly("2027-02-28/2027-03-31/OPEN");
    }

    @Test
    void invalidStartDaysAreRefusedAndNothingChanges() throws SQLException {
        grant();
        service.current(user);
        for (Integer bad : new Integer[] {null, 0, 32, -1}) {
            assertThatThrownBy(() -> service.changeStartDay(user, bad)).isInstanceOf(InvalidStartDayException.class);
        }
        assertThat(rows(user)).containsExactly("2026-09-25/2026-10-25/OPEN");
    }

    @Test
    void refusedWithoutConsentAndWritesNothing() throws SQLException {
        assertThatThrownBy(() -> service.current(user)).isInstanceOf(ConsentRequiredException.class);
        assertThatThrownBy(() -> service.changeStartDay(user, 1)).isInstanceOf(ConsentRequiredException.class);
        assertThat(rows(user)).isEmpty();
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT cycle_start_day FROM users WHERE id = '" + user + "'");
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(25);
        }
    }

    @Test
    void databaseRefusesAPlanCycleWriteBeforeConsentEvenBypassingTheService() throws SQLException {
        try (Connection c = TestDb.asAppUser(user); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO plan_cycle (user_id, cycle_start, cycle_end, start_day) "
                        + "VALUES (?, DATE '2026-09-25', DATE '2026-10-25', 25)")) {
            c.createStatement().execute("SELECT set_config('app.consent_version', '"
                    + ConsentStatement.CURRENT.version() + "', false)");
            ps.setObject(1, user);
            assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
        }
    }

    @Test
    void anotherUserNeverSeesOrTouchesMyCycleThroughTheService() throws SQLException {
        grant();
        service.current(user);
        UUID other = UUID.randomUUID();
        insertUser(other);
        current = other;
        consents.grant(other, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
        PlanCycleView v = service.changeStartDay(other, 1);
        assertThat(v.cycleStart()).isEqualTo(LocalDate.of(2026, 10, 1));
        assertThat(rows(user)).containsExactly("2026-09-25/2026-10-25/OPEN");
        assertThat(rows(other)).containsExactly("2026-10-01/2026-11-01/OPEN");
    }

    @Test
    void deletedUserIsRefusedAndNothingIsServed() throws SQLException {
        grant();
        admin("DELETE FROM users WHERE id = '" + user + "'");
        assertThatThrownBy(() -> service.current(user)).isInstanceOf(ConsentRequiredException.class);
    }
}
