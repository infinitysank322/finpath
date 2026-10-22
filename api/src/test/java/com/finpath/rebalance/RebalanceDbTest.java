package com.finpath.rebalance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.finpath.auth.CurrentUser;
import com.finpath.auth.CurrentUserIdArgumentResolver;
import com.finpath.budget.BudgetRepository;
import com.finpath.db.TestDb;
import com.finpath.dpdp.ConsentRequiredException;
import com.finpath.dpdp.ConsentService;
import com.finpath.dpdp.ConsentStatement;
import com.finpath.expense.TransactionRepository;
import com.finpath.expense.TransactionRepository.Draft;
import com.finpath.rls.RlsTransactionManager;
import com.finpath.rules.BudgetRules;
import com.finpath.rules.CycleCalendar;
import com.finpath.rules.Money;
import com.finpath.rules.TransactionHash.Direction;
import com.finpath.rules.UnplannedExpense;
import com.finpath.rules.WaterfallAllocator.Line;
import com.finpath.rules.WaterfallAllocator.Result;
import com.finpath.rules.WaterfallAllocator.Step;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T41 acceptance: the endpoint's figures equal a direct UnplannedExpense.rebalance call on the same
 * inputs, under RLS (skipped without FINPATH_TEST_DB_URL). Amounts are synthetic, not persona figures.
 * Clock fixed at 2026-10-22 (Asia/Kolkata); start day 1, so the cycle is 2026-10-01 to 2026-11-01.
 */
class RebalanceDbTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-22");
    private static final LocalDate CYCLE_START = LocalDate.parse("2026-10-01");
    private static final long SURPLUS = 3_000_000L;
    private static final String ALLOCATIONS = "[{\"step\":\"EMERGENCY_FUND_ONE_MONTH\",\"ref\":null,"
            + "\"amountPaise\":1000000},{\"step\":\"HIGH_INTEREST_DEBT\",\"ref\":\"loan-1\",\"amountPaise\":800000},"
            + "{\"step\":\"HABIT_SIP\",\"ref\":null,\"amountPaise\":500000},"
            + "{\"step\":\"LONG_TERM_SIP\",\"ref\":null,\"amountPaise\":700000}]";

    private JdbcTemplate jdbc;
    private ConsentService consents;
    private BudgetRepository budgets;
    private TransactionRepository transactions;
    private RebalanceService service;
    private MockMvc mvc;
    private int seq;

    @BeforeEach
    void setUp() {
        TestDb.requireAndMigrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(TestDb.URL, TestDb.USER, TestDb.PASSWORD);
        jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new RlsTransactionManager(ds, CurrentUser::id));
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("jdbc", jdbc);
        bf.registerSingleton("tx", tx);
        consents = new ConsentService(bf.getBeanProvider(JdbcTemplate.class),
                bf.getBeanProvider(TransactionTemplate.class));
        transactions = new TransactionRepository(() -> jdbc, () -> tx);
        budgets = new BudgetRepository(() -> jdbc, () -> tx);
        ObjectMapper om = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        Clock clock = Clock.fixed(Instant.parse("2026-10-22T06:30:00Z"), ZoneId.of("Asia/Kolkata"));
        service = new RebalanceService(new RebalanceRepository(() -> jdbc, () -> tx), budgets, transactions,
                consents, om, clock);
        mvc = MockMvcBuilders.standaloneSetup(new RebalanceController(service))
                .setControllerAdvice(new RebalanceExceptionHandler())
                .setCustomArgumentResolvers(new CurrentUserIdArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om)).build();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void as(UUID user) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(user.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private UUID newUser(boolean consent) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, name, email, cycle_start_day) VALUES (?, 'T', ?, 1)")) {
            ps.setObject(1, id);
            ps.setString(2, id + "@example.test");
            ps.executeUpdate();
        }
        if (consent) {
            as(id);
            consents.grant(id, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
        }
        return id;
    }

    private void plan(UUID user, Long surplus, String allocations, String status) throws Exception {
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement("INSERT INTO plan_cycle "
                + "(user_id, cycle_start, cycle_end, start_day, surplus, allocations, status) "
                + "VALUES (?, '2026-10-01', '2026-11-01', 1, ?, ?::jsonb, ?)")) {
            ps.setObject(1, user);
            ps.setObject(2, surplus);
            ps.setString(3, allocations);
            ps.setString(4, status);
            ps.executeUpdate();
        }
    }

    private void spend(UUID user, Direction d, String category, long paise, LocalDate date) {
        as(user);
        transactions.insert(user, new Draft(date, paise, d, category, "UPI", null, null,
                "rebalance-test-" + user + "-" + (++seq)));
    }

    private void budget(UUID user, String category, long paise) {
        as(user);
        budgets.upsert(user, CYCLE_START, category, paise);
    }

    private static Result directPlan() {
        return new Result(Money.ofPaise(SURPLUS), Money.ZERO, List.of(
                new Line(Step.EMERGENCY_FUND_ONE_MONTH, null, Money.ofPaise(1_000_000L)),
                new Line(Step.HIGH_INTEREST_DEBT, "loan-1", Money.ofPaise(800_000L)),
                new Line(Step.HABIT_SIP, null, Money.ofPaise(500_000L)),
                new Line(Step.LONG_TERM_SIP, null, Money.ofPaise(700_000L))));
    }

    private UUID seeded() throws Exception {
        UUID u = newUser(true);
        plan(u, SURPLUS, ALLOCATIONS, "OPEN");
        budget(u, "DINING", 400_000L);
        budget(u, "FUEL", 300_000L);
        spend(u, Direction.OUT, "DINING", 250_000L, LocalDate.parse("2026-10-10"));
        spend(u, Direction.OUT, "FUEL", 100_001L, LocalDate.parse("2026-10-12"));
        spend(u, Direction.IN, "REFUND", 999_999L, LocalDate.parse("2026-10-12"));
        spend(u, Direction.OUT, "FUEL", 777_777L, LocalDate.parse("2026-09-30"));
        spend(u, Direction.OUT, "FUEL", 888_888L, LocalDate.parse("2026-11-01"));
        return u;
    }

    @Test
    void figuresEqualADirectRulesCallAndNothingIsStored() throws Exception {
        UUID u = seeded();
        long expense = 900_000L;
        Integer txBefore = jdbc.queryForObject("SELECT count(*) FROM transaction WHERE user_id = ?", Integer.class, u);
        as(u);
        RebalanceService.Preview p = service.preview(u, expense);

        UnplannedExpense.Result direct = UnplannedExpense.rebalance(directPlan(), Money.ofPaise(expense),
                BudgetRules.sum(List.of(Money.ofPaise(400_000L), Money.ofPaise(300_000L))),
                BudgetRules.sum(List.of(Money.ofPaise(250_000L), Money.ofPaise(100_001L))),
                CycleCalendar.cycleFor(TODAY, 1), TODAY);

        assertThat(p.primaryAbsorbingStep()).isEqualTo("LONG_TERM_SIP").isEqualTo(direct.absorbed().get(0).step().name());
        assertThat(p.absorbed()).extracting(RebalanceService.Line::step, RebalanceService.Line::amountPaise)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("LONG_TERM_SIP", 700_000L),
                        org.assertj.core.groups.Tuple.tuple("HABIT_SIP", 200_000L));
        assertThat(p.absorbed()).hasSameSizeAs(direct.absorbed());
        for (int i = 0; i < direct.absorbed().size(); i++) {
            assertThat(p.absorbed().get(i).step()).isEqualTo(direct.absorbed().get(i).step().name());
            assertThat(p.absorbed().get(i).ref()).isEqualTo(direct.absorbed().get(i).ref());
            assertThat(p.absorbed().get(i).amountPaise()).isEqualTo(direct.absorbed().get(i).amount().paise());
        }
        assertThat(p.uncoveredPaise()).isEqualTo(direct.uncovered().paise());
        assertThat(p.surplusAfterPaise()).isEqualTo(direct.surplusAfter().paise());
        assertThat(p.safeToSpendBeforePaise()).isEqualTo(direct.safeToSpendBefore().paise());
        assertThat(p.safeToSpendAfterPaise()).isEqualTo(direct.safeToSpendAfter().paise());
        assertThat(p.safeToSpendAfterPaise()).isLessThan(p.safeToSpendBeforePaise());
        assertThat(p.lines()).hasSameSizeAs(direct.lines());
        for (int i = 0; i < direct.lines().size(); i++) {
            assertThat(p.lines().get(i).step()).isEqualTo(direct.lines().get(i).step().name());
            assertThat(p.lines().get(i).ref()).isEqualTo(direct.lines().get(i).ref());
            assertThat(p.lines().get(i).amountPaise()).isEqualTo(direct.lines().get(i).amount().paise());
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transaction WHERE user_id = ?", Integer.class, u))
                .isEqualTo(txBefore);
        assertThat(jdbc.queryForObject("SELECT allocations::text FROM plan_cycle WHERE user_id = ?", String.class, u))
                .contains("700000");
    }

    @Test
    void expenseBeyondEveryAllocationIsReportedUncovered() throws Exception {
        UUID u = seeded();
        as(u);
        RebalanceService.Preview p = service.preview(u, SURPLUS + 5L);
        UnplannedExpense.Result direct = UnplannedExpense.rebalance(directPlan(), Money.ofPaise(SURPLUS + 5L),
                Money.ofPaise(700_000L), Money.ofPaise(350_001L), CycleCalendar.cycleFor(TODAY, 1), TODAY);
        assertThat(p.uncoveredPaise()).isEqualTo(5L).isEqualTo(direct.uncovered().paise());
        assertThat(p.surplusAfterPaise()).isZero();
        assertThat(p.lines()).isEmpty();
        assertThat(p.safeToSpendAfterPaise()).isEqualTo(direct.safeToSpendAfter().paise());
    }

    @Test
    void httpResponseCarriesTheSameFigures() throws Exception {
        UUID u = seeded();
        as(u);
        RebalanceService.Preview p = service.preview(u, 900_000L);
        mvc.perform(post("/plan/rebalance").contentType(MediaType.APPLICATION_JSON).content("{\"amountPaise\":900000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryAbsorbingStep").value("LONG_TERM_SIP"))
                .andExpect(jsonPath("$.safeToSpendBeforePaise").value(p.safeToSpendBeforePaise()))
                .andExpect(jsonPath("$.safeToSpendAfterPaise").value(p.safeToSpendAfterPaise()))
                .andExpect(jsonPath("$.surplusAfterPaise").value(p.surplusAfterPaise()));
    }

    @Test
    void noOpenPlanOrEmptyAllocationsOrNullSurplusIsNotFound() throws Exception {
        UUID none = newUser(true);
        UUID empty = newUser(true);
        plan(empty, SURPLUS, "[]", "OPEN");
        UUID nullSurplus = newUser(true);
        plan(nullSurplus, null, ALLOCATIONS, "OPEN");
        UUID closed = newUser(true);
        plan(closed, SURPLUS, ALLOCATIONS, "CLOSED");
        for (UUID u : List.of(none, empty, nullSurplus, closed)) {
            as(u);
            assertThatThrownBy(() -> service.preview(u, 100L)).isInstanceOf(RebalanceNotFoundException.class);
        }
    }

    @Test
    void anotherUsersPlanIsNeverUsed() throws Exception {
        UUID owner = seeded();
        UUID other = newUser(true);
        as(other);
        assertThatThrownBy(() -> service.preview(other, 100L)).isInstanceOf(RebalanceNotFoundException.class);
        assertThat(owner).isNotEqualTo(other);
    }

    @Test
    void consentAndAmountAreEnforced() throws Exception {
        UUID noConsent = newUser(false);
        plan(noConsent, SURPLUS, ALLOCATIONS, "OPEN");
        as(noConsent);
        assertThatThrownBy(() -> service.preview(noConsent, 100L)).isInstanceOf(ConsentRequiredException.class);
        UUID u = seeded();
        as(u);
        for (Long bad : new Long[] {null, 0L, -5L}) {
            assertThatThrownBy(() -> service.preview(u, bad)).isInstanceOf(InvalidRebalanceException.class);
        }
    }
}
