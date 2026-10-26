package com.finpath.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.finpath.auth.CurrentUser;
import com.finpath.auth.CurrentUserIdArgumentResolver;
import com.finpath.budget.BudgetRepository;
import com.finpath.budget.BudgetService;
import com.finpath.budget.BudgetService.BudgetInput;
import com.finpath.checklist.ChecklistService;
import com.finpath.db.TestDb;
import com.finpath.dpdp.ConsentService;
import com.finpath.dpdp.ConsentStatement;
import com.finpath.expense.ExpenseService;
import com.finpath.expense.TransactionRepository;
import com.finpath.expense.TransactionRepository.Draft;
import com.finpath.fi.FiPlannerService;
import com.finpath.fi.FiProjectionService;
import com.finpath.goal.EmergencyFundService;
import com.finpath.goal.GoalRepository;
import com.finpath.holding.HoldingRepository;
import com.finpath.holding.HoldingService;
import com.finpath.holding.HoldingService.HoldingInput;
import com.finpath.income.IncomeService;
import com.finpath.income.IncomeService.IncomeInput;
import com.finpath.loan.LoanRepository;
import com.finpath.loan.LoanService;
import com.finpath.loan.LoanService.LoanInput;
import com.finpath.networth.NetWorthService;
import com.finpath.plan.PlanCycleService;
import com.finpath.recurring.RecurringService;
import com.finpath.rls.RlsTransactionManager;
import com.finpath.rules.IncomeCalculator.Frequency;
import com.finpath.rules.IncomeCalculator.Type;
import com.finpath.rules.RuleConfig;
import com.finpath.rules.TransactionHash.Direction;
import com.finpath.rules.golden.GoldenFixture;
import com.finpath.rules.golden.GoldenKeys;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
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
 * T74 against the scratch Postgres (skipped without FINPATH_TEST_DB_URL, see TestDb) with the real services under
 * RLS. System clocks throughout, so "today" is the real Indian date and the rollover case is built by seeding a
 * still-OPEN previous cycle. Persona figures come from the committed anonymised fixture, never literals.
 */
class DashboardApiDbTest {

    private static final GoldenFixture FX = GoldenFixture.shared();
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ConsentService consents;
    private PlanCycleService cycles;
    private IncomeService income;
    private HoldingService holdings;
    private LoanService loans;
    private BudgetService budgets;
    private TransactionRepository transactions;
    private NetWorthService netWorth;
    private EmergencyFundService emergencyFund;
    private ChecklistService checklist;
    private MockMvc mvc;
    private final LocalDate today = LocalDate.now(IST);
    private final LocalDate cycleStart = today.withDayOfMonth(1);
    private int seq;

    @BeforeEach
    void setUp() {
        TestDb.requireAndMigrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(TestDb.URL, TestDb.USER, TestDb.PASSWORD);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new RlsTransactionManager(ds, CurrentUser::id));
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("jdbc", jdbc);
        bf.registerSingleton("tx", tx);
        var jdbcP = bf.getBeanProvider(JdbcTemplate.class);
        var txP = bf.getBeanProvider(TransactionTemplate.class);
        RuleConfig config = RuleConfig.defaults();
        consents = new ConsentService(jdbcP, txP);
        cycles = new PlanCycleService(jdbcP, txP, consents, JSON);
        income = new IncomeService(jdbcP, txP, consents);
        transactions = new TransactionRepository(() -> jdbc, () -> tx);
        BudgetRepository budgetRepo = new BudgetRepository(() -> jdbc, () -> tx);
        budgets = new BudgetService(budgetRepo, transactions, consents, config);
        loans = new LoanService(new LoanRepository(jdbcP, txP), consents, income, config);
        RecurringService recurring = new RecurringService(transactions, jdbcP, txP, consents);
        emergencyFund = new EmergencyFundService(loans, recurring, new GoalRepository(() -> jdbc, () -> tx), consents,
                transactions);
        holdings = new HoldingService(new HoldingRepository(() -> jdbc, () -> tx), consents);
        netWorth = new NetWorthService(jdbcP, txP, consents);
        checklist = new ChecklistService(jdbcP, txP, consents, cycles);
        ExpenseService expenses = new ExpenseService(transactions, jdbcP, txP, consents);
        FiProjectionService fi = new FiProjectionService(new FiPlannerService(config), holdings, JSON);
        DashboardService service = new DashboardService(consents, cycles, budgets, netWorth, fi, income, loans,
                emergencyFund, expenses, checklist);
        mvc = MockMvcBuilders.standaloneSetup(new DashboardController(service))
                .setControllerAdvice(new DashboardExceptionHandler())
                .setCustomArgumentResolvers(new CurrentUserIdArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(JSON)).build();
    }

    // ---- seeding -----------------------------------------------------------------------------

    private UUID newUser(boolean consent) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, name, email, cycle_start_day) VALUES (?, 'T', ?, 1)")) {
            ps.setObject(1, id);
            ps.setString(2, id + "@example.test");
            ps.executeUpdate();
        }
        as(id);
        if (consent) {
            consents.grant(id, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
        }
        return id;
    }

    private static void as(UUID user) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(user.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private static void admin(String sql, Object... args) throws SQLException {
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static long count(String sql, Object... args) throws SQLException {
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ResultSet rs = ps.executeQuery();
            rs.next();
            return rs.getLong(1);
        }
    }

    /** Salary + rental, a holding, the home loan, one budget line and spend (one budgeted, one not). */
    private void seedPersona(UUID user) throws SQLException {
        as(user);
        income.create(user, new IncomeInput(Type.SALARY, Frequency.MONTHLY, FX.paise(GoldenKeys.INCOME_SALARY),
                cycleStart, null));
        income.create(user, new IncomeInput(Type.RENTAL, Frequency.MONTHLY, FX.paise(GoldenKeys.INCOME_RENTAL),
                cycleStart, null));
        holdings.create(user, new HoldingInput("mf", "Index fund", FX.paise(GoldenKeys.EMERGENCY_CURRENT),
                FX.paise(GoldenKeys.EMERGENCY_CURRENT), 0L));
        loans.create(user, new LoanInput("Bank", "emi", FX.paise(GoldenKeys.LOAN_HOME_PRINCIPAL),
                FX.text(GoldenKeys.LOAN_HOME_RATE), FX.paise(GoldenKeys.LOAN_HOME_EMI_STATED),
                (long) FX.integer(GoldenKeys.LOAN_HOME_EMIS_LEFT), null, null, null), true);
        budgets.put(user, "GROCERIES", cycleStart, new BudgetInput(FX.paise(GoldenKeys.BUDGET_CATEGORY)));
        spend(user, "GROCERIES", FX.paise(GoldenKeys.BUDGET_SPENT));
        spend(user, "TRAVEL", FX.paise(GoldenKeys.BUDGET_SPENT));
        cycles.current(user);
        admin("UPDATE plan_cycle SET surplus = ?, allocations = ?::jsonb WHERE user_id = ? AND status = 'OPEN'",
                FX.paise(GoldenKeys.SURPLUS_MONTHLY),
                "[{\"step\":\"HABIT_SIP\",\"ref\":null,\"amountPaise\":1000000}]", user);
    }

    private void spend(UUID user, String category, long paise) {
        as(user);
        transactions.insert(user, new Draft(cycleStart, paise, Direction.OUT, category, "UPI", "NEED", null,
                "dash-test-" + user + "-" + (++seq)));
    }

    private JsonNode dashboard(UUID user) throws Exception {
        as(user);
        return JSON.readTree(mvc.perform(get("/dashboard")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    // ---- tests -------------------------------------------------------------------------------

    @Test
    void everyFieldEqualsTheOwningServiceOutput() throws Exception {
        UUID user = newUser(true);
        seedPersona(user);
        JsonNode d = dashboard(user);
        as(user);

        var lines = budgets.status(user, null).lines();
        long budgetSum = lines.stream().mapToLong(l -> l.budgetPaise()).sum();
        long lineSpent = lines.stream().mapToLong(l -> l.spentPaise()).sum();
        assertThat(d.at("/budget/status").asText()).isEqualTo("OK");
        assertThat(d.at("/budget/budgetPaise").asLong()).isEqualTo(budgetSum);
        assertThat(d.at("/budget/spentPaise").asLong()).as("unbudgeted TRAVEL spend counts in the total")
                .isEqualTo(lineSpent + FX.paise(GoldenKeys.BUDGET_SPENT));
        long remaining = d.at("/budget/remainingPaise").asLong();
        assertThat(remaining).isEqualTo(d.at("/budget/budgetPaise").asLong() - d.at("/budget/spentPaise").asLong());
        assertThat(d.at("/budget/safeToSpendPerDayPaise").asLong())
                .isEqualTo(remaining <= 0 ? 0 : Math.floorDiv(remaining, d.at("/cycle/daysLeft").asLong()));

        assertThat(d.at("/asOf").asText()).isEqualTo(today.toString());
        assertThat(d.at("/cycle/start").asText()).isEqualTo(cycleStart.toString());
        assertThat(d.at("/cycle/daysElapsed").asInt() + d.at("/cycle/daysLeft").asInt())
                .isEqualTo(d.at("/cycle/lengthDays").asInt());

        assertThat(JSON.readTree(JSON.writeValueAsString(netWorth.series(user).points())))
                .isEqualTo(d.at("/netWorth/series"));
        assertThat(d.at("/netWorth/nowPaise").asLong()).isEqualTo(netWorth.current(user).netWorthPaise())
                .isEqualTo(FX.paise(GoldenKeys.EMERGENCY_CURRENT) - FX.paise(GoldenKeys.LOAN_HOME_PRINCIPAL));
        assertThat(d.at("/stats/netWorthPaise").asLong()).isEqualTo(d.at("/netWorth/nowPaise").asLong());

        var guard = loans.guard(user);
        assertThat(d.at("/stats/emiToIncome/ratio").asText()).isEqualTo(guard.ratio());
        assertThat(d.at("/stats/emiToIncome/status").asText()).isEqualTo(guard.status());
        assertThat(guard.totalEmiPaise()).isEqualTo(FX.paise(GoldenKeys.LOAN_HOME_EMI_STATED));
        assertThat(guard.monthlyIncomePaise()).isEqualTo(FX.paise(GoldenKeys.INCOME_MONTHLY));

        var ef = emergencyFund.size(user, null);
        assertThat(d.at("/stats/efMonthsCovered").isNull() ? null : d.at("/stats/efMonthsCovered").asText())
                .isEqualTo(ef.monthsCovered());
        assertThat(d.at("/stats/streak").asInt()).isEqualTo(checklist.streak(user).streak());
        assertThat(d.at("/stats/savingsRatePct").asText())
                .isEqualTo(FX.decimal(GoldenKeys.SAVINGS_RATE_PCT).toPlainString());
        assertThat(d.at("/stats/debtFree/start").isTextual()).isTrue();

        assertThat(d.at("/fi/status").asText()).isEqualTo("OK");
        assertThat(d.at("/fi/bands").size()).isEqualTo(3);
        assertThat(d.at("/fi/deltaYearsVsLastCycle").isNull()).isTrue();
        assertThat(d.at("/fi/disclaimer").asText()).isNotBlank();
    }

    @Test
    void firstCallAfterRolloverIncludesTheJustClosedCycleSnapshot() throws Exception {
        UUID user = newUser(true);
        as(user);
        holdings.create(user, new HoldingInput("mf", "Index fund", FX.paise(GoldenKeys.EMERGENCY_CURRENT),
                FX.paise(GoldenKeys.EMERGENCY_CURRENT), 0L));
        LocalDate previous = cycleStart.minusMonths(1);
        admin("INSERT INTO plan_cycle (user_id, cycle_start, cycle_end, start_day, status) "
                + "VALUES (?, ?, ?, 1, 'OPEN')", user, previous, cycleStart);
        assertThat(count("SELECT count(*) FROM net_worth_snapshot WHERE user_id = ?", user)).isZero();

        JsonNode first = dashboard(user);

        assertThat(first.at("/cycle/start").asText()).isEqualTo(cycleStart.toString());
        JsonNode series = first.at("/netWorth/series");
        assertThat(series.size()).isEqualTo(1);
        assertThat(series.get(series.size() - 1).get("cycleStart").asText()).isEqualTo(previous.toString());
        assertThat(series.get(0).get("netWorthPaise").asLong()).isEqualTo(first.at("/netWorth/nowPaise").asLong());
        assertThat(dashboard(user)).as("idempotent").isEqualTo(first);
        assertThat(count("SELECT count(*) FROM net_worth_snapshot WHERE user_id = ?", user)).isEqualTo(1);
    }

    @Test
    void userWithOnlyConsentGetsNotSetUpSectionsNotAnError() throws Exception {
        UUID user = newUser(true);
        JsonNode d = dashboard(user);
        assertThat(d.at("/budget/status").asText()).isEqualTo("NOT_SET_UP");
        assertThat(d.at("/budget/budgetPaise").isNull()).isTrue();
        assertThat(d.at("/fi/status").asText()).isEqualTo("NOT_SET_UP");
        assertThat(d.at("/fi/bands").size()).isZero();
        assertThat(d.at("/fi/disclaimer").asText()).isNotBlank();
        assertThat(d.at("/stats/savingsRatePct").isNull()).isTrue();
        assertThat(d.at("/stats/debtFree").isNull()).isTrue();
        assertThat(d.at("/netWorth/nowPaise").asLong()).isZero();
        assertThat(d.at("/netWorth/series").size()).isZero();
        assertThat(d.at("/cycle/lengthDays").asInt()).isPositive();
    }

    @Test
    void withoutConsentIsForbidden() throws Exception {
        UUID user = newUser(false);
        as(user);
        mvc.perform(get("/dashboard")).andExpect(status().isForbidden());
    }

    @Test
    void otherUsersDataNeverAppearsAndAUserIdParameterIsIgnored() throws Exception {
        UUID a = newUser(true);
        seedPersona(a);
        UUID b = newUser(true);
        as(b);
        String body = mvc.perform(get("/dashboard").param("userId", a.toString())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode d = JSON.readTree(body);
        assertThat(d.at("/netWorth/nowPaise").asLong()).isZero();
        assertThat(d.at("/budget/status").asText()).isEqualTo("NOT_SET_UP");
        assertThat(d.at("/netWorth/series").size()).isZero();
        assertThat(body).doesNotContain(a.toString())
                .doesNotContain(String.valueOf(FX.paise(GoldenKeys.EMERGENCY_CURRENT)))
                .doesNotContain(String.valueOf(FX.paise(GoldenKeys.LOAN_HOME_PRINCIPAL)));
    }

    /** Warm p95 over 50 calls on the persona dataset; the measurement is printed for the PR. */
    @Test
    void warmP95IsUnderSixHundredMilliseconds() throws Exception {
        UUID user = newUser(true);
        seedPersona(user);
        dashboard(user); // warm the pool and the plan
        List<Long> millis = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            long t0 = System.nanoTime();
            dashboard(user);
            millis.add((System.nanoTime() - t0) / 1_000_000);
        }
        Collections.sort(millis);
        long p95 = millis.get((int) Math.ceil(millis.size() * 0.95) - 1);
        System.err.println("[perf] GET /dashboard warm p95=" + p95 + " ms, max=" + millis.get(millis.size() - 1) + " ms");
        assertThat(p95).isLessThan(600L);
    }
}
