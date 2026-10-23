package com.finpath.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpath.db.TestDb;
import com.finpath.dpdp.ConsentService;
import com.finpath.dpdp.ConsentStatement;
import com.finpath.networth.NetWorthService.PointView;
import com.finpath.networth.NetWorthService;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Snapshot written at cycle close, once, from holdings and loans, under RLS (needs the scratch DB). */
class NetWorthSnapshotDbTest {

    private static volatile UUID current;

    static final class MutableClock extends Clock {
        private Instant now;

        void set(LocalDate date) {
            now = date.atTime(12, 0).atZone(ZoneId.of("Asia/Kolkata")).toInstant();
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }

    private final MutableClock clock = new MutableClock();
    private PlanCycleService cycles;
    private NetWorthService series;
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
        ConsentService consents = new ConsentService(bf.getBeanProvider(JdbcTemplate.class),
                bf.getBeanProvider(TransactionTemplate.class));
        cycles = new PlanCycleService(() -> jdbc, () -> tx, consents, new ObjectMapper(), clock);
        series = new NetWorthService(() -> jdbc, () -> tx, consents);
        user = UUID.randomUUID();
        admin("INSERT INTO users (id, name, email) VALUES ('" + user + "', 'T', '" + user + "@example.test')");
        current = user;
        consents.grant(user, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
        clock.set(LocalDate.of(2026, 10, 22));
    }

    private static void admin(String sql) throws SQLException {
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(sql)) {
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    private void holding(long value) throws SQLException {
        admin("INSERT INTO holding (user_id, asset_class, name, current_value_paise) VALUES ('" + user
                + "', 'mf', 'Index fund', " + value + ")");
    }

    private void loan(long principal) throws SQLException {
        admin("INSERT INTO loan (user_id, lender, type, principal_outstanding_paise, rate, emi_paise, emis_left) "
                + "VALUES ('" + user + "', 'Bank', 'emi', " + principal + ", 0.1, 1000, 12)");
    }

    @Test
    void closingACycleWritesExactlyOneSnapshotEqualToAssetsMinusLiabilities() throws SQLException {
        holding(7_000_000);
        holding(3_000_000);
        loan(4_000_000);
        cycles.current(user);
        assertThat(count("SELECT count(*) FROM net_worth_snapshot")).isZero();

        clock.set(LocalDate.of(2026, 10, 26));
        cycles.current(user);
        cycles.current(user);

        List<PointView> points = series.series(user).points();
        assertThat(points).containsExactly(new PointView(LocalDate.of(2026, 9, 25), 10_000_000L, 4_000_000L,
                6_000_000L));
        assertThat(count("SELECT count(*) FROM net_worth_snapshot")).isEqualTo(1);
    }

    @Test
    void netWorthMayBeNegative() throws SQLException {
        loan(500);
        cycles.current(user);
        clock.set(LocalDate.of(2026, 10, 26));
        cycles.current(user);
        assertThat(series.series(user).points()).extracting(PointView::netWorthPaise).containsExactly(-500L);
    }

    @Test
    void aCycleSupersededByAStartDayChangeGetsNoSnapshot() throws SQLException {
        cycles.current(user);
        cycles.changeStartDay(user, 1);
        assertThat(count("SELECT count(*) FROM net_worth_snapshot")).isZero();
    }

    @Test
    void aClosedCycleCannotBeBackfilledAndEachClosedCycleKeepsItsOwnPoint() throws SQLException {
        admin("INSERT INTO plan_cycle (user_id, cycle_start, cycle_end, start_day, status) VALUES ('" + user
                + "', DATE '2026-08-25', DATE '2026-09-25', 25, 'CLOSED')");
        cycles.current(user);
        clock.set(LocalDate.of(2026, 10, 26));
        cycles.current(user);
        assertThat(series.series(user).points()).extracting(PointView::cycleStart)
                .containsExactly(LocalDate.of(2026, 9, 25));
        assertThat(count("SELECT count(*) FROM net_worth_snapshot s JOIN plan_cycle p ON p.id = s.plan_cycle_id "
                + "WHERE p.cycle_start = DATE '2026-08-25'")).isZero();
    }

    @Test
    void aSecondSnapshotForOneCycleIsRejectedByTheDatabase() throws SQLException {
        holding(100);
        cycles.current(user);
        clock.set(LocalDate.of(2026, 10, 26));
        cycles.current(user);
        assertThatThrownBy(() -> admin("INSERT INTO net_worth_snapshot (user_id, plan_cycle_id, cycle_start, "
                + "assets_paise, liabilities_paise, net_worth_paise) SELECT user_id, plan_cycle_id, cycle_start, 1, 0, 1 "
                + "FROM net_worth_snapshot")).isInstanceOf(SQLException.class);
    }
}
