package com.finpath.reminder;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.finpath.db.TestDb;
import com.finpath.dpdp.ConsentStatement;
import com.finpath.reminder.ReminderMessage.Kind;
import com.finpath.rls.RlsScope;
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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** T72 over the scratch database under RLS (skipped without one): at-most-once reminders, fixed bodies, isolation. */
class ReminderServiceDbTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String LENDER = "Swiggy HDFC Card";

    private final List<ReminderMessage> sent = Collections.synchronizedList(new ArrayList<>());
    private ReminderSink sink = sent::add;
    private MutableClock clock;
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private ReminderRepository repo;

    /** A clock whose date the test moves. */
    static final class MutableClock extends Clock {
        volatile LocalDate date;

        MutableClock(LocalDate date) {
            this.date = date;
        }

        @Override
        public ZoneId getZone() {
            return IST;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return date.atTime(10, 0).atZone(IST).toInstant();
        }
    }

    @BeforeEach
    void setUp() {
        TestDb.requireAndMigrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(TestDb.URL, TestDb.USER, TestDb.PASSWORD);
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new RlsTransactionManager(ds, RlsScope::principalOrScoped));
        repo = new ReminderRepository(() -> jdbc, () -> tx);
        clock = new MutableClock(LocalDate.of(2027, 4, 30));
    }

    private ReminderService service(ReminderRepository r) {
        return new ReminderService(r, m -> sink.send(m), clock);
    }

    private static UUID user(int cycleStartDay, String consentVersion) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = TestDb.admin()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (id, name, email, cycle_start_day) VALUES (?, 'T', ?, ?)")) {
                ps.setObject(1, id);
                ps.setString(2, id + "@example.test");
                ps.setInt(3, cycleStartDay);
                ps.executeUpdate();
            }
            if (consentVersion != null) {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO consent (user_id, purpose, "
                        + "statement_version, purpose_statement) VALUES (?, 'FINANCIAL_PLANNING', ?, 'x')")) {
                    ps.setObject(1, id);
                    ps.setString(2, consentVersion);
                    ps.executeUpdate();
                }
            }
        }
        return id;
    }

    private static UUID consented(int cycleStartDay) throws SQLException {
        return user(cycleStartDay, ConsentStatement.CURRENT.version());
    }

    private static UUID loan(UUID user, Integer dueDay) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement("INSERT INTO loan (id, user_id, "
                + "lender, type, principal_outstanding_paise, rate, emi_paise, emis_left, due_day) "
                + "VALUES (?, ?, ?, 'emi', 100000000, 0.1, 5000000, 12, ?)")) {
            ps.setObject(1, id);
            ps.setObject(2, user);
            ps.setString(3, LENDER);
            ps.setObject(4, dueDay);
            ps.executeUpdate();
        }
        return id;
    }

    private static List<String> rows(String where) throws SQLException {
        List<String> out = new ArrayList<>();
        try (Connection c = TestDb.admin(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT reminder_key || '@' || due_date FROM reminder_sent "
                        + where + " ORDER BY due_date, reminder_key")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    @Test
    void loanDueOnThe5thRemindsOnceOn30AprilForThe5thOfMay() throws Exception {
        UUID u = consented(15);
        UUID l = loan(u, 5);
        assertThat(service(repo).runOnce()).isEqualTo(1);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).kind()).isEqualTo(Kind.LOAN_DUE);
        assertThat(sent.get(0).userId()).isEqualTo(u);
        assertThat(rows("")).containsExactly("loan_due:" + l + "@2027-05-05");
    }

    @Test
    void beforeTheReminderDateNothingIsSent() throws Exception {
        UUID u = consented(15);
        loan(u, 5);
        clock.date = LocalDate.of(2027, 4, 29);
        assertThat(service(repo).runOnce()).isZero();
        assertThat(rows("")).isEmpty();
    }

    @Test
    void aLoanWithoutADueDayProducesNothing() throws Exception {
        UUID u = consented(15);
        loan(u, null);
        assertThat(service(repo).runOnce()).isZero();
        assertThat(sent).isEmpty();
        assertThat(rows("")).isEmpty();
    }

    @Test
    void aPaidOffLoanProducesNothing() throws Exception {
        UUID u = consented(15);
        UUID l = loan(u, 5);
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE loan SET principal_outstanding_paise = 0 WHERE id = '" + l + "'");
        }
        assertThat(service(repo).runOnce()).isZero();
    }

    @Test
    void aSecondRunTheSameDayAddsNothingAndTheNextMonthAddsANewRow() throws Exception {
        UUID u = consented(15);
        loan(u, 5);
        ReminderService svc = service(repo);
        assertThat(svc.runOnce()).isEqualTo(1);
        assertThat(svc.runOnce()).isZero();
        clock.date = LocalDate.of(2027, 5, 4);
        assertThat(svc.runOnce()).isZero();
        assertThat(rows("")).hasSize(1);
        clock.date = LocalDate.of(2027, 5, 31);
        assertThat(svc.runOnce()).isEqualTo(1);
        assertThat(rows("")).hasSize(2).last().asString().endsWith("@2027-06-05");
        assertThat(sent).hasSize(2);
    }

    @Test
    void theCycleDayEventFiresOncePerCycleAndAgainInTheNext() throws Exception {
        consented(25);
        clock.date = LocalDate.of(2026, 10, 25);
        ReminderService svc = service(repo);
        assertThat(svc.runOnce()).isEqualTo(1);
        assertThat(sent.get(0).kind()).isEqualTo(Kind.CYCLE_START);
        clock.date = LocalDate.of(2026, 10, 26);
        assertThat(svc.runOnce()).isZero();
        clock.date = LocalDate.of(2026, 11, 10);
        assertThat(svc.runOnce()).isZero();
        clock.date = LocalDate.of(2026, 11, 25);
        assertThat(svc.runOnce()).isEqualTo(1);
        assertThat(rows("")).containsExactly("cycle_start@2026-10-25", "cycle_start@2026-11-25");
    }

    @Test
    void usersWithoutLiveConsentGetNothing() throws Exception {
        UUID none = user(15, null);
        loan(none, 5);
        UUID withdrawn = consented(15);
        loan(withdrawn, 5);
        UUID older = user(15, "2020-01-old");
        loan(older, 5);
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE consent SET withdrawn_at = now() WHERE user_id = '" + withdrawn + "'");
        }
        assertThat(service(repo).runOnce()).isZero();
        assertThat(sent).isEmpty();
        assertThat(rows("")).isEmpty();
    }

    @Test
    void oneUsersRunNeverReadsAnotherUsersLoans() throws Exception {
        UUID a = consented(15);
        UUID b = consented(15);
        UUID bLoan = loan(b, 5);
        // Even when asked for B's id explicitly, a transaction scoped to A sees no B row.
        assertThat(RlsScope.runAs(a, () -> repo.dueLoans(b))).isEmpty();
        assertThat(RlsScope.runAs(a, () -> repo.cycleStartDay(b))).isEmpty();
        assertThat(RlsScope.runAs(a, () -> repo.claim(a, "cycle_start", LocalDate.of(2027, 4, 15)))).isTrue();
        assertThat(RlsScope.runAs(b, () -> repo.dueLoans(b))).extracting(ReminderRepository.DueLoan::id)
                .containsExactly(bLoan);
        assertThat(service(repo).runOnce()).isEqualTo(1);
        assertThat(sent).extracting(ReminderMessage::userId).containsExactly(b);
    }

    @Test
    void aSlowFirstClaimOverlappedByASecondRunSendsExactlyOnce() throws Exception {
        UUID u = consented(15);
        loan(u, 5);
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        // Simulates the 12 s Neon wake: the claim is committed but its caller is held up (latch, no real sleep).
        ReminderRepository slow = new ReminderRepository(() -> jdbc, () -> tx) {
            @Override
            public boolean claim(UUID userId, String key, LocalDate dueDate) {
                boolean owns = super.claim(userId, key, dueDate);
                if (first.compareAndSet(true, false)) {
                    claimed.countDown();
                    try {
                        assertThat(resume.await(30, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return owns;
            }
        };
        ReminderService svc = service(slow);
        int[] firstRun = new int[1];
        Thread t = new Thread(() -> firstRun[0] = svc.runOnce());
        t.start();
        assertThat(claimed.await(30, TimeUnit.SECONDS)).isTrue();
        assertThat(svc.runOnce()).as("the overlapping run loses the claim").isZero();
        resume.countDown();
        t.join(30_000);
        assertThat(firstRun[0]).isEqualTo(1);
        assertThat(sent).hasSize(1);
        assertThat(rows("")).hasSize(1);
    }

    @Test
    void aClaimThatCommittedButTimedOutClientSideIsNeverSentTwice() throws Exception {
        UUID u = consented(15);
        loan(u, 5);
        AtomicBoolean first = new AtomicBoolean(true);
        ReminderRepository flaky = new ReminderRepository(() -> jdbc, () -> tx) {
            @Override
            public boolean claim(UUID userId, String key, LocalDate dueDate) {
                boolean owns = super.claim(userId, key, dueDate);
                if (first.compareAndSet(true, false)) {
                    throw new org.springframework.dao.QueryTimeoutException("client gave up after the commit");
                }
                return owns;
            }
        };
        ReminderService svc = service(flaky);
        assertThat(svc.runOnce()).isZero();
        assertThat(svc.runOnce()).isZero();
        assertThat(sent.size()).isLessThanOrEqualTo(1);
        assertThat(rows("")).hasSize(1);
    }

    @Test
    void aSinkFailureReleasesTheClaimAndTheNextRunSendsExactlyOnce() throws Exception {
        UUID u = consented(15);
        loan(u, 5);
        AtomicBoolean failNext = new AtomicBoolean(true);
        sink = m -> {
            if (failNext.getAndSet(false)) {
                throw new IllegalStateException("push gateway down");
            }
            sent.add(m);
        };
        ReminderService svc = service(repo);
        assertThat(svc.runOnce()).isZero();
        assertThat(rows("")).isEmpty();
        assertThat(svc.runOnce()).isEqualTo(1);
        assertThat(svc.runOnce()).isZero();
        assertThat(sent).hasSize(1);
        assertThat(rows("")).hasSize(1);
    }

    @Test
    void aFailureForOneUserDoesNotStopTheNextUser() throws Exception {
        UUID broken = consented(15);
        loan(broken, 5);
        UUID fine = consented(15);
        loan(fine, 5);
        ReminderRepository flaky = new ReminderRepository(() -> jdbc, () -> tx) {
            @Override
            public List<DueLoan> dueLoans(UUID userId) {
                if (userId.equals(broken)) {
                    throw new org.springframework.dao.DataAccessResourceFailureException("connection timed out");
                }
                return super.dueLoans(userId);
            }
        };
        assertThat(service(flaky).runOnce()).isEqualTo(1);
        assertThat(sent).extracting(ReminderMessage::userId).containsExactly(fine);
        assertThat(service(repo).runOnce()).isEqualTo(1);
        assertThat(sent).extracting(ReminderMessage::userId).containsExactlyInAnyOrder(fine, broken);
    }

    @Test
    void bodiesAreTheFixedConstantsWithNoFiguresAndLogsCarryNoIdsOrText() throws Exception {
        UUID u = consented(15);
        loan(u, 5);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        List<Logger> loggers = List.of((Logger) LoggerFactory.getLogger(ReminderService.class),
                (Logger) LoggerFactory.getLogger(LoggingReminderSink.class));
        loggers.forEach(l -> {
            l.setLevel(ch.qos.logback.classic.Level.INFO);
            l.addAppender(logs);
        });
        try {
            sink = new LoggingReminderSink();
            clock.date = LocalDate.of(2027, 4, 30);
            ReminderService svc = service(repo);
            svc.runOnce();
            sink = sent::add;
            UUID v = consented(30);
            clock.date = LocalDate.of(2027, 4, 30);
            svc.runOnce();
        } finally {
            loggers.forEach(l -> l.detachAppender(logs));
        }
        assertThat(sent).isNotEmpty();
        for (ReminderMessage m : sent) {
            assertThat(m.title()).isEqualTo(m.kind().title());
            assertThat(m.body()).isEqualTo(m.kind().body());
            assertThat(m.title() + " " + m.body()).doesNotContain("₹", "Rs", LENDER, "Swiggy")
                    .doesNotContainPattern("[0-9]");
        }
        assertThat(Set.of(Kind.values())).containsExactlyInAnyOrder(Kind.LOAN_DUE, Kind.CYCLE_START);
        assertThat(logs.list).isNotEmpty();
        for (ILoggingEvent e : logs.list) {
            String text = e.getFormattedMessage();
            assertThat(text).doesNotContain(u.toString(), LENDER, "Swiggy", Kind.LOAN_DUE.body(), Kind.LOAN_DUE.title());
        }
    }

    @Test
    void theClockZoneIsIndiaNotTheServerZone() throws Exception {
        UUID u = consented(15);
        loan(u, 5);
        // 29 Apr 19:00 UTC is already 30 Apr 00:30 in Kolkata.
        Clock utc = Clock.fixed(Instant.parse("2027-04-29T19:00:00Z"), ZoneOffset.UTC);
        assertThat(new ReminderService(repo, sent::add, utc).runOnce()).isEqualTo(1);
    }
}
