package com.finpath.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.finpath.auth.CurrentUserIdArgumentResolver;
import com.finpath.budget.BudgetService;
import com.finpath.budget.BudgetService.CycleSummary;
import com.finpath.checklist.ChecklistService;
import com.finpath.checklist.ChecklistService.StreakView;
import com.finpath.config.ColdStartFirstConnectTest.SlowFirstConnectDriver;
import com.finpath.dashboard.DashboardController;
import com.finpath.dashboard.DashboardService;
import com.finpath.dpdp.ConsentService;
import com.finpath.expense.ExpenseService;
import com.finpath.expense.ExpenseService.TagSummary;
import com.finpath.fi.FiPlannerService;
import com.finpath.fi.FiProjectionService;
import com.finpath.fi.FiProjectionService.Projection;
import com.finpath.goal.EmergencyFundService;
import com.finpath.goal.EmergencyFundService.EmergencyFundView;
import com.finpath.income.IncomeService;
import com.finpath.income.IncomeService.CycleTotalView;
import com.finpath.loan.LoanService;
import com.finpath.loan.LoanService.GuardView;
import com.finpath.networth.NetWorthService;
import com.finpath.networth.NetWorthService.SeriesView;
import com.finpath.networth.NetWorthSnapshots.Position;
import com.finpath.plan.PlanCycleService;
import com.finpath.plan.PlanCycleService.PlanCycleView;
import com.finpath.rules.RuleConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.PropertySource;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * T74 cold start: GET /dashboard against a pool whose first connect takes 12 s (the T04 stub driver and the real
 * prod Hikari settings). The first service call of the dashboard pays the wake; the response must be the same 200
 * body as a warm call, with no error status, no ERROR log and no retry (each collaborator called once per request).
 * Real wall-clock time on purpose, as in ColdStartFirstConnectTest.
 */
class DashboardColdStartTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 1);
    private static final long FIRST_CONNECT_MILLIS = 12_000;

    @Test
    void twelveSecondFirstConnectStillReturnsTheFullBodyWithoutAnError() throws Exception {
        MockEnvironment env = new MockEnvironment();
        for (PropertySource<?> doc : ProdProfileYaml.prodDocuments()) {
            env.getPropertySources().addLast(doc);
        }
        HikariConfig hikari = Binder.get(env).bind("spring.datasource.hikari", Bindable.of(HikariConfig.class)).get();
        hikari.setJdbcUrl(SlowFirstConnectDriver.URL);
        hikari.setInitializationFailTimeout(-1);
        assertThat(hikari.getConnectionTimeout()).isGreaterThanOrEqualTo(FIRST_CONNECT_MILLIS);

        PlanCycleService cycles = mock(PlanCycleService.class);
        AtomicInteger cycleCalls = new AtomicInteger();
        SlowFirstConnectDriver driver = new SlowFirstConnectDriver();
        DriverManager.registerDriver(driver);
        Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try (HikariDataSource ds = new HikariDataSource(hikari)) {
            when(cycles.current(any())).thenAnswer(i -> {
                cycleCalls.incrementAndGet();
                try (Connection c = ds.getConnection()) { // the first query after the idle period pays the wake
                    assertThat(c.isValid(1)).isTrue();
                }
                return new PlanCycleView(START, END, 1, "OPEN", 100L,
                        new ObjectMapper().readTree("[]"), 21, 10);
            });
            DashboardService service = service(cycles);
            MockMvc mvc = MockMvcBuilders.standaloneSetup(new DashboardController(service))
                    .setCustomArgumentResolvers(new CurrentUserIdArgumentResolver())
                    .setMessageConverters(new MappingJackson2HttpMessageConverter(new ObjectMapper()
                            .findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)))
                    .build();
            UUID user = UUID.randomUUID();
            Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(user.toString()).build();
            SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));

            long started = System.nanoTime();
            String cold = mvc.perform(get("/dashboard")).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString();
            long coldMillis = (System.nanoTime() - started) / 1_000_000;
            String warm = mvc.perform(get("/dashboard")).andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString();

            assertThat(coldMillis).as("the simulated wake really happened").isGreaterThanOrEqualTo(FIRST_CONNECT_MILLIS - 500);
            assertThat(cold).isEqualTo(warm).contains("\"asOf\":\"2026-10-22\"");
            assertThat(cycleCalls.get()).as("one call per request, no retry layer").isEqualTo(2);
            verify(cycles, times(2)).current(user);
        } finally {
            SecurityContextHolder.clearContext();
            root.detachAppender(logs);
            DriverManager.deregisterDriver(driver);
        }
        assertThat(logs.list).as("no ERROR-level log for a slow first connect")
                .noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.ERROR));
    }

    private static DashboardService service(PlanCycleService cycles) throws Exception {
        BudgetService budgets = mock(BudgetService.class);
        when(budgets.cycleSummary(any(), any())).thenReturn(Optional.of(
                new CycleSummary(1000L, 400L, 600L, "0.4000", com.finpath.rules.BudgetRules.Level.OK, 60L)));
        NetWorthService netWorth = mock(NetWorthService.class);
        when(netWorth.current(any())).thenReturn(new Position(900L, 400L, 500L));
        when(netWorth.series(any())).thenReturn(new SeriesView(List.of()));
        IncomeService income = mock(IncomeService.class);
        when(income.cycleTotal(any())).thenReturn(new CycleTotalView(START, END, 1000L, List.of()));
        EmergencyFundService ef = mock(EmergencyFundService.class);
        when(ef.size(any(), any())).thenReturn(new EmergencyFundView(5000L, 5000L, 3, 12345L, 2000L, "0.40", 13000L));
        FiProjectionService fi = mock(FiProjectionService.class);
        when(fi.projection(any(), org.mockito.ArgumentMatchers.anyLong(), any(), any(), any())).thenReturn(
                new Projection(true, new FiPlannerService(RuleConfig.defaults()).plan(5000L, 0L, 1_000_000L, 10_000L,
                        null, 1, LocalDate.of(2026, 10, 22))));
        LoanService loans = mock(LoanService.class);
        when(loans.guard(any())).thenReturn(new GuardView("0.40", "OK", 10L, 20L, 1000L));
        ExpenseService expenses = mock(ExpenseService.class);
        when(expenses.tagSummary(any(), any(), any())).thenReturn(new TagSummary(START, END, 50L, 30L, 20L, 0L, 100L));
        ChecklistService checklist = mock(ChecklistService.class);
        when(checklist.streak(any())).thenReturn(new StreakView(2, List.of()));
        return new DashboardService(mock(ConsentService.class), cycles, budgets, netWorth, fi, income, loans, ef,
                expenses, checklist);
    }
}
