package com.finpath.income;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finpath.db.TestDb;
import com.finpath.dpdp.ConsentRequiredException;
import com.finpath.dpdp.ConsentService;
import com.finpath.dpdp.ConsentStatement;
import com.finpath.income.IncomeService.CycleTotalView;
import com.finpath.income.IncomeService.IncomeInput;
import com.finpath.income.IncomeService.IncomeView;
import com.finpath.rls.RlsTransactionManager;
import com.finpath.rules.IncomeCalculator.Frequency;
import com.finpath.rules.IncomeCalculator.Type;
import com.finpath.rules.golden.GoldenFixture;
import com.finpath.rules.golden.GoldenKeys;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** IncomeService against a real database under RLS (needs the scratch DB; skipped without one). */
class IncomeDbTest {

    private static final GoldenFixture FX = GoldenFixture.shared();
    private static final long SALARY = FX.paise(GoldenKeys.INCOME_SALARY);
    private static final long RENTAL = FX.paise(GoldenKeys.INCOME_RENTAL);
    private static final long BONUS = FX.paise(GoldenKeys.INCOME_BONUS);

    private static volatile UUID current;

    private IncomeService service;
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
        consents = new ConsentService(bf.getBeanProvider(JdbcTemplate.class),
                bf.getBeanProvider(TransactionTemplate.class));
        // 2026-10-22 in Asia/Kolkata; the default start day 25 puts the cycle at 25 Sep .. 25 Oct.
        Clock clock = Clock.fixed(LocalDate.of(2026, 10, 22).atTime(12, 0).atZone(ZoneId.of("Asia/Kolkata"))
                .toInstant(), ZoneId.of("Asia/Kolkata"));
        service = new IncomeService(() -> jdbc, () -> tx, consents, clock);
        user = newUser();
        current = user;
    }

    private static UUID newUser() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, name, email) VALUES (?, 'T', ?)")) {
            ps.setObject(1, id);
            ps.setString(2, id + "@example.test");
            ps.executeUpdate();
        }
        return id;
    }

    private void grant(UUID u) {
        consents.grant(u, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
    }

    private static IncomeInput monthly(Type type, long paise, String start) {
        return new IncomeInput(type, Frequency.MONTHLY, paise, LocalDate.parse(start), null);
    }

    @Test
    void salaryPlusRentalIsTheCycleTotalFromTheFixtureAmounts() {
        grant(user);
        service.create(user, monthly(Type.SALARY, SALARY, "2026-01-01"));
        service.create(user, monthly(Type.RENTAL, RENTAL, "2026-03-01"));

        CycleTotalView t = service.cycleTotal(user);

        assertThat(t.cycleStart()).isEqualTo(LocalDate.of(2026, 9, 25));
        assertThat(t.cycleEndExclusive()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(t.totalPaise()).isEqualTo(FX.paise(GoldenKeys.INCOME_MONTHLY));
        assertThat(t.items()).hasSize(2);
    }

    @Test
    void aOneOffBonusCountsOnlyInTheCycleItWasReceivedIn() {
        grant(user);
        service.create(user, monthly(Type.SALARY, SALARY, "2026-01-01"));
        IncomeView inside = service.create(user, new IncomeInput(Type.BONUS, Frequency.ONE_OFF, BONUS,
                LocalDate.of(2026, 10, 1), null));
        IncomeView previous = service.create(user, new IncomeInput(Type.BONUS, Frequency.ONE_OFF, BONUS,
                LocalDate.of(2026, 9, 24), null));
        IncomeView next = service.create(user, new IncomeInput(Type.BONUS, Frequency.ONE_OFF, BONUS,
                LocalDate.of(2026, 10, 25), null));

        assertThat(service.cycleTotal(user).totalPaise()).isEqualTo(SALARY + BONUS);
        assertThat(inside.countsInCurrentCycle()).isTrue();
        assertThat(previous.countsInCurrentCycle()).isFalse();
        assertThat(next.countsInCurrentCycle()).isFalse();
        assertThat(service.list(user)).extracting(IncomeView::startDate).isSortedAccordingTo(
                java.util.Comparator.reverseOrder());
    }

    @Test
    void updateAndDeleteChangeTheTotal() {
        grant(user);
        IncomeView v = service.create(user, monthly(Type.SALARY, SALARY, "2026-01-01"));
        service.replace(user, v.id(), new IncomeInput(Type.SALARY, Frequency.MONTHLY, SALARY, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 9, 24)));   // ended before the cycle
        assertThat(service.cycleTotal(user).totalPaise()).isZero();
        service.delete(user, v.id());
        assertThat(service.list(user)).isEmpty();
        assertThatThrownBy(() -> service.delete(user, v.id())).isInstanceOf(IncomeNotFoundException.class);
    }

    @Test
    void invalidInputIsRefusedAndNothingIsWritten() {
        grant(user);
        LocalDate d = LocalDate.of(2026, 10, 1);
        IncomeInput[] bad = {
            new IncomeInput(Type.SALARY, Frequency.MONTHLY, 0L, d, null),
            new IncomeInput(Type.SALARY, Frequency.MONTHLY, -5L, d, null),
            new IncomeInput(Type.SALARY, Frequency.MONTHLY, null, d, null),
            new IncomeInput(null, Frequency.MONTHLY, 1L, d, null),
            new IncomeInput(Type.SALARY, Frequency.MONTHLY, 1L, null, null),
            new IncomeInput(Type.SALARY, Frequency.MONTHLY, 1L, d, d.minusDays(1)),
            new IncomeInput(Type.BONUS, Frequency.ONE_OFF, 1L, d, d.plusDays(1))};
        for (IncomeInput in : bad) {
            assertThatThrownBy(() -> service.create(user, in)).isInstanceOf(InvalidIncomeException.class);
        }
        assertThat(service.list(user)).isEmpty();
    }

    @Test
    void writesBeforeConsentAreRefusedWithNoRows() {
        assertThatThrownBy(() -> service.create(user, monthly(Type.SALARY, SALARY, "2026-01-01")))
                .isInstanceOf(ConsentRequiredException.class);
        assertThat(service.list(user)).isEmpty();
    }

    @Test
    void anotherUserCannotReadChangeOrDeleteMyRowsThroughTheService() throws SQLException {
        grant(user);
        IncomeView mine = service.create(user, monthly(Type.SALARY, SALARY, "2026-01-01"));
        UUID other = newUser();
        current = other;
        grant(other);

        assertThat(service.list(other)).isEmpty();
        assertThat(service.cycleTotal(other).totalPaise()).isZero();
        assertThatThrownBy(() -> service.replace(other, mine.id(), monthly(Type.SALARY, 1, "2026-01-01")))
                .isInstanceOf(IncomeNotFoundException.class);
        assertThatThrownBy(() -> service.delete(other, mine.id())).isInstanceOf(IncomeNotFoundException.class);

        current = user;
        assertThat(service.list(user)).extracting(IncomeView::amountPaise).containsExactly(SALARY);
    }

    @Test
    void databaseRejectsRowsBreakingTheChecksEvenBypassingTheService() throws SQLException {
        try (Connection c = TestDb.admin()) {
            for (String values : new String[] {"'SALARY','MONTHLY',0,DATE '2026-01-01',NULL",
                    "'SALARY','WEEKLY',1,DATE '2026-01-01',NULL", "'LOTTERY','MONTHLY',1,DATE '2026-01-01',NULL",
                    "'BONUS','ONE_OFF',1,DATE '2026-01-01',DATE '2026-02-01'",
                    "'SALARY','MONTHLY',1,DATE '2026-02-01',DATE '2026-01-01'"}) {
                assertThatThrownBy(() -> c.createStatement().execute("INSERT INTO income_source (user_id, type, "
                        + "frequency, amount_paise, start_date, end_date) VALUES ('" + user + "'," + values + ")"))
                        .isInstanceOf(SQLException.class).hasMessageContaining("check constraint");
            }
        }
    }
}
