package com.finpath.dashboard;

import com.finpath.budget.BudgetService;
import com.finpath.budget.BudgetService.CycleSummary;
import com.finpath.checklist.ChecklistService;
import com.finpath.dpdp.ConsentService;
import com.finpath.expense.ExpenseService;
import com.finpath.expense.ExpenseService.TagSummary;
import com.finpath.fi.FiPlannerService.BandView;
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
import com.finpath.networth.NetWorthSnapshots.Position;
import com.finpath.plan.PlanCycleService;
import com.finpath.plan.PlanCycleService.PlanCycleView;
import com.finpath.rules.CycleCalendar;
import com.finpath.rules.Money;
import com.finpath.rules.Statistics;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the home-screen aggregate (T74). No arithmetic lives here: every figure comes from an
 * existing service or from the rules package. Calls run sequentially on purpose (a Neon wake is paid once,
 * by the first query; a slow first connect is a slow 200, never an error) and nothing is cached or
 * retried. {@link PlanCycleService#current} runs first because it closes an elapsed cycle and writes
 * that cycle's net-worth snapshot, which the series read afterwards must include. asOf is the date that
 * call used, so every section shares one "today". Logs nothing.
 */
@Service
public class DashboardService {

    static final String OK = "OK";
    static final String NOT_SET_UP = "NOT_SET_UP";

    public record CycleSection(LocalDate start, LocalDate endExclusive, int lengthDays, int daysElapsed,
            int daysLeft) {
    }

    /** Figures are null when status is NOT_SET_UP. usedFraction is a decimal string, null for a zero budget. */
    public record BudgetSection(String status, Long budgetPaise, Long spentPaise, Long remainingPaise,
            String usedFraction, String level, Long safeToSpendPerDayPaise) {
    }

    public record NetWorthSection(long nowPaise, List<PointView> series) {
    }

    public record BaseFi(Integer fiYears, LocalDate fiDate) {
    }

    /** fiYears null means not reached within the solve horizon. deltaYearsVsLastCycle is not stored yet: null. */
    public record FiSection(String status, List<BandView> bands, BaseFi base, Integer deltaYearsVsLastCycle,
            Object assumptions, String disclaimer) {
    }

    public record EmiToIncome(String ratio, String status) {
    }

    public record StatsSection(String savingsRatePct, EmiToIncome emiToIncome, String discretionarySharePct,
            String efMonthsCovered, PayoffCycle debtFree, int streak, long netWorthPaise) {
    }

    public record DashboardView(LocalDate asOf, CycleSection cycle, BudgetSection budget, NetWorthSection netWorth,
            FiSection fi, StatsSection stats) {
    }

    private final ConsentService consents;
    private final PlanCycleService cycles;
    private final BudgetService budgets;
    private final NetWorthService netWorth;
    private final FiProjectionService fi;
    private final IncomeService income;
    private final LoanService loans;
    private final EmergencyFundService emergencyFund;
    private final ExpenseService expenses;
    private final ChecklistService checklist;

    public DashboardService(ConsentService consents, PlanCycleService cycles, BudgetService budgets,
            NetWorthService netWorth, FiProjectionService fi, IncomeService income, LoanService loans,
            EmergencyFundService emergencyFund, ExpenseService expenses, ChecklistService checklist) {
        this.consents = consents;
        this.cycles = cycles;
        this.budgets = budgets;
        this.netWorth = netWorth;
        this.fi = fi;
        this.income = income;
        this.loans = loans;
        this.emergencyFund = emergencyFund;
        this.expenses = expenses;
        this.checklist = checklist;
    }

    public DashboardView dashboard(UUID userId) {
        consents.require(userId);
        PlanCycleView view = cycles.current(userId);
        CycleCalendar.Cycle cycle = new CycleCalendar.Cycle(view.cycleStart(), view.cycleEndExclusive());
        LocalDate asOf = view.cycleStart().plusDays(view.daysElapsed());

        BudgetSection budget = budgets.cycleSummary(userId, asOf).map(DashboardService::budgetSection)
                .orElseGet(() -> new BudgetSection(NOT_SET_UP, null, null, null, null, null, null));

        Position position = netWorth.current(userId);
        NetWorthSection worth = new NetWorthSection(position.netWorthPaise(), netWorth.series(userId).points());

        CycleTotalView incomeNow = income.cycleTotal(userId);
        EmergencyFundView ef = emergencyFund.size(userId, null);
        Projection projection = fi.projection(userId, ef.monthlyOutflowPaise(), incomeNow, view.allocations(), asOf);

        GuardView guard = loans.guard(userId);
        TagSummary tags = expenses.tagSummary(userId, cycle.start(), cycle.lastDay());

        StatsSection stats = new StatsSection(
                view.surplusPaise() == null ? null
                        : plain(Statistics.savingsRate(Money.ofPaise(view.surplusPaise()),
                                Money.ofPaise(incomeNow.totalPaise()))),
                new EmiToIncome(guard.ratio(), guard.status()),
                plain(Statistics.discretionaryShare(Money.ofPaise(tags.wantPaise()),
                        Money.ofPaise(tags.wastePaise()), Money.ofPaise(tags.totalPaise()))),
                ef.monthsCovered(), loans.debtFree(userId, asOf), checklist.streak(userId).streak(),
                position.netWorthPaise());

        return new DashboardView(asOf,
                new CycleSection(cycle.start(), cycle.endExclusive(), cycle.lengthDays(), view.daysElapsed(),
                        view.daysLeft()),
                budget, worth, fiSection(projection), stats);
    }

    private static BudgetSection budgetSection(CycleSummary s) {
        return new BudgetSection(OK, s.budgetPaise(), s.spentPaise(), s.remainingPaise(), s.usedFraction(),
                s.level().name(), s.safeToSpendPerDayPaise());
    }

    private static FiSection fiSection(Projection p) {
        if (!p.setUp()) {
            return new FiSection(NOT_SET_UP, List.of(), null, null, p.plan().assumptions(), p.plan().disclaimer());
        }
        BandView base = p.plan().bands().stream().filter(b -> "BASE".equals(b.band())).findFirst().orElseThrow();
        return new FiSection(OK, p.plan().bands(), new BaseFi(base.fiYears(), base.fiDate()), null,
                p.plan().assumptions(), p.plan().disclaimer());
    }

    private static String plain(BigDecimal v) {
        return v == null ? null : v.toPlainString();
    }
}
