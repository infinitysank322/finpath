package com.finpath.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpath.budget.BudgetService;
import com.finpath.budget.BudgetService.CycleSummary;
import com.finpath.checklist.ChecklistService;
import com.finpath.checklist.ChecklistService.StreakView;
import com.finpath.dashboard.DashboardService.DashboardView;
import com.finpath.dpdp.ConsentRequiredException;
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
import com.finpath.loan.LoanService.PayoffCycle;
import com.finpath.networth.NetWorthService;
import com.finpath.networth.NetWorthService.PointView;
import com.finpath.networth.NetWorthService.SeriesView;
import com.finpath.networth.NetWorthSnapshots.Position;
import com.finpath.plan.PlanCycleService;
import com.finpath.plan.PlanCycleService.PlanCycleView;
import com.finpath.rules.BudgetRules.Level;
import com.finpath.rules.RuleConfig;
import com.finpath.rules.golden.GoldenFixture;
import com.finpath.rules.golden.GoldenKeys;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/** T74 orchestration with the owning services mocked: ordering, consent, mapping, NOT_SET_UP. Figures from the fixture. */
class DashboardServiceTest {

    private static final GoldenFixture FX = GoldenFixture.shared();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = LocalDate.of(2026, 11, 1);
    private static final int ELAPSED = 21;
    private static final LocalDate AS_OF = START.plusDays(ELAPSED);
    private static final long SURPLUS = FX.paise(GoldenKeys.SURPLUS_MONTHLY);
    private static final long INCOME = FX.paise(GoldenKeys.INCOME_MONTHLY);

    private final UUID user = UUID.randomUUID();
    private ConsentService consents;
    private PlanCycleService cycles;
    private BudgetService budgets;
    private NetWorthService netWorth;
    private FiProjectionService fi;
    private IncomeService income;
    private LoanService loans;
    private EmergencyFundService ef;
    private ExpenseService expenses;
    private ChecklistService checklist;
    private DashboardService service;

    @BeforeEach
    void setUp() throws Exception {
        consents = mock(ConsentService.class);
        cycles = mock(PlanCycleService.class);
        budgets = mock(BudgetService.class);
        netWorth = mock(NetWorthService.class);
        fi = mock(FiProjectionService.class);
        income = mock(IncomeService.class);
        loans = mock(LoanService.class);
        ef = mock(EmergencyFundService.class);
        expenses = mock(ExpenseService.class);
        checklist = mock(ChecklistService.class);
        service = new DashboardService(consents, cycles, budgets, netWorth, fi, income, loans, ef, expenses,
                checklist);

        when(cycles.current(user)).thenReturn(cycleView(SURPLUS));
        when(budgets.cycleSummary(user, AS_OF)).thenReturn(java.util.Optional.of(
                new CycleSummary(1000L, 400L, 600L, "0.4000", Level.OK, 60L)));
        when(netWorth.current(user)).thenReturn(new Position(900L, 400L, 500L));
        when(netWorth.series(user)).thenReturn(new SeriesView(List.of(
                new PointView(LocalDate.of(2026, 9, 1), 800L, 400L, 400L))));
        when(income.cycleTotal(user)).thenReturn(new CycleTotalView(START, END, INCOME, List.of()));
        when(ef.size(user, null)).thenReturn(new EmergencyFundView(5000L, 5000L, 3, 12345L, 2000L, "0.40", 13000L));
        FiPlannerService planner = new FiPlannerService(RuleConfig.defaults());
        when(fi.projection(any(), any(Long.class), any(), any(), any())).thenReturn(new Projection(true,
                planner.plan(5000L, 0L, 1_000_000L, 10_000L, null, 1, AS_OF)));
        when(loans.guard(user)).thenReturn(new GuardView("0.40", "OK", 10L, 20L, INCOME));
        when(loans.debtFree(user, AS_OF)).thenReturn(new PayoffCycle(LocalDate.of(2040, 3, 1),
                LocalDate.of(2040, 4, 1)));
        when(expenses.tagSummary(user, START, END.minusDays(1)))
                .thenReturn(new TagSummary(START, END, 50L, 30L, 20L, 0L, 100L));
        when(checklist.streak(user)).thenReturn(new StreakView(4, List.of()));
    }

    private static PlanCycleView cycleView(Long surplus) throws Exception {
        return new PlanCycleView(START, END, 1, "OPEN", surplus, JSON.readTree("[]"), ELAPSED,
                (int) java.time.temporal.ChronoUnit.DAYS.between(AS_OF, END));
    }

    @Test
    void closesTheElapsedCycleBeforeReadingTheNetWorthSeries() {
        service.dashboard(user);
        InOrder order = inOrder(consents, cycles, netWorth);
        order.verify(consents).require(user);
        order.verify(cycles).current(user);
        order.verify(netWorth).series(user);
    }

    @Test
    void noConsentTouchesNothingElse() {
        doThrow(new ConsentRequiredException()).when(consents).require(user);
        assertThatThrownBy(() -> service.dashboard(user)).isInstanceOf(ConsentRequiredException.class);
        verifyNoInteractions(cycles, budgets, netWorth, fi, income, loans, ef, expenses, checklist);
    }

    @Test
    void everySectionComesFromTheOwningServiceForThisUserOnly() {
        DashboardView v = service.dashboard(user);
        assertThat(v.asOf()).isEqualTo(AS_OF);
        assertThat(v.cycle().lengthDays()).isEqualTo(31);
        assertThat(v.cycle().daysElapsed() + v.cycle().daysLeft()).isEqualTo(v.cycle().lengthDays());
        assertThat(v.budget().status()).isEqualTo("OK");
        assertThat(v.budget().spentPaise()).isEqualTo(400L);
        assertThat(v.budget().level()).isEqualTo("OK");
        assertThat(v.budget().safeToSpendPerDayPaise()).isEqualTo(60L);
        assertThat(v.netWorth().nowPaise()).isEqualTo(500L);
        assertThat(v.netWorth().series()).hasSize(1);
        assertThat(v.stats().netWorthPaise()).isEqualTo(v.netWorth().nowPaise());
        assertThat(v.stats().emiToIncome().ratio()).isEqualTo("0.40");
        assertThat(v.stats().efMonthsCovered()).isEqualTo("0.40");
        assertThat(v.stats().streak()).isEqualTo(4);
        assertThat(v.stats().debtFree().start()).isEqualTo(LocalDate.of(2040, 3, 1));
        assertThat(v.stats().discretionarySharePct()).isEqualTo("50.0");
        assertThat(v.fi().bands()).hasSize(3);
        assertThat(v.fi().base().fiYears()).isEqualTo(v.fi().bands().get(1).fiYears());
        assertThat(v.fi().disclaimer()).isNotBlank();
        assertThat(v.fi().assumptions()).isNotNull();
    }

    @Test
    void savingsRateIsSurplusOverCurrentCycleIncomeMatchingTheFixture() {
        assertThat(service.dashboard(user).stats().savingsRatePct())
                .isEqualTo(FX.decimal(GoldenKeys.SAVINGS_RATE_PCT).toPlainString());
    }

    @Test
    void savingsRateIsNullUntilTheAllocationHasRun() throws Exception {
        when(cycles.current(user)).thenReturn(cycleView(null));
        assertThat(service.dashboard(user).stats().savingsRatePct()).isNull();
    }

    @Test
    void fiDeltaIsNotShippedYet() {
        assertThat(service.dashboard(user).fi().deltaYearsVsLastCycle()).isNull();
    }

    @Test
    void notSetUpSectionsCarryNoFabricatedZeros() throws Exception {
        when(budgets.cycleSummary(user, AS_OF)).thenReturn(java.util.Optional.empty());
        when(cycles.current(user)).thenReturn(cycleView(null));
        when(loans.debtFree(user, AS_OF)).thenReturn(null);
        FiPlannerService planner = new FiPlannerService(RuleConfig.defaults());
        when(fi.projection(any(), any(Long.class), any(), any(), any())).thenReturn(new Projection(false,
                planner.plan(0L, 0L, 0L, 0L, null, 1, AS_OF)));
        when(expenses.tagSummary(user, START, END.minusDays(1)))
                .thenReturn(new TagSummary(START, END, 0L, 0L, 0L, 0L, 0L));
        DashboardView v = service.dashboard(user);
        assertThat(v.budget().status()).isEqualTo("NOT_SET_UP");
        assertThat(v.budget().budgetPaise()).isNull();
        assertThat(v.budget().safeToSpendPerDayPaise()).isNull();
        assertThat(v.fi().status()).isEqualTo("NOT_SET_UP");
        assertThat(v.fi().base()).isNull();
        assertThat(v.fi().bands()).isEmpty();
        assertThat(v.fi().disclaimer()).isNotBlank();
        assertThat(v.stats().savingsRatePct()).isNull();
        assertThat(v.stats().discretionarySharePct()).isNull();
        assertThat(v.stats().debtFree()).isNull();
    }
}
