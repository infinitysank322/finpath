package com.finpath.rebalance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpath.budget.BudgetRepository;
import com.finpath.dpdp.ConsentService;
import com.finpath.expense.TransactionRepository;
import com.finpath.rebalance.RebalanceRepository.Plan;
import com.finpath.rules.BudgetRules;
import com.finpath.rules.CycleCalendar;
import com.finpath.rules.Money;
import com.finpath.rules.UnplannedExpense;
import com.finpath.rules.WaterfallAllocator;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Read-only preview of an unplanned mid-cycle expense (FR-07). Loads the open plan, the cycle's
 * budget lines and OUT spend, and hands them to {@link UnplannedExpense#rebalance}; every figure in
 * the response comes from the rules package (sums via {@link BudgetRules}). Stores nothing and logs
 * no amounts.
 */
@Service
public class RebalanceService {

    static final ZoneId CYCLE_ZONE = ZoneId.of("Asia/Kolkata");

    public record Line(String step, String ref, long amountPaise) {
    }

    /** primaryAbsorbingStep is null when nothing was absorbed (empty plan lines cannot occur: 404). */
    public record Preview(List<Line> absorbed, String primaryAbsorbingStep, long uncoveredPaise,
            long surplusAfterPaise, List<Line> lines, long safeToSpendBeforePaise, long safeToSpendAfterPaise) {
    }

    private final RebalanceRepository plans;
    private final BudgetRepository budgets;
    private final TransactionRepository transactions;
    private final ConsentService consents;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public RebalanceService(RebalanceRepository plans, BudgetRepository budgets, TransactionRepository transactions,
            ConsentService consents, ObjectMapper mapper) {
        this(plans, budgets, transactions, consents, mapper, Clock.system(CYCLE_ZONE));
    }

    RebalanceService(RebalanceRepository plans, BudgetRepository budgets, TransactionRepository transactions,
            ConsentService consents, ObjectMapper mapper, Clock clock) {
        this.plans = plans;
        this.budgets = budgets;
        this.transactions = transactions;
        this.consents = consents;
        this.mapper = mapper;
        this.clock = clock;
    }

    public Preview preview(UUID userId, Long amountPaise) {
        if (amountPaise == null || amountPaise <= 0) {
            throw new InvalidRebalanceException();
        }
        consents.require(userId);
        LocalDate today = LocalDate.now(clock);
        return budgets.inTransaction(() -> {
            CycleCalendar.Cycle cycle = budgets.cycleOf(userId, today);
            Plan plan = plans.openPlan(userId, cycle.start()).orElseThrow(RebalanceNotFoundException::new);
            List<WaterfallAllocator.Line> planned = AllocationsJson.parse(mapper, plan.allocationsJson());
            if (plan.surplusPaise() == null || planned.isEmpty()) {
                throw new RebalanceNotFoundException();
            }
            WaterfallAllocator.Result allocation = new WaterfallAllocator.Result(
                    Money.ofPaise(plan.surplusPaise()), Money.ZERO, planned);
            Money totalBudget = BudgetRules.sum(budgets.listForCycle(userId, cycle.start()).stream()
                    .map(r -> Money.ofPaise(r.budgetPaise())).toList());
            Money totalSpent = BudgetRules.sum(BudgetRules.spentByCategory(
                    transactions.listBetween(userId, cycle.start(), cycle.endExclusive()).stream()
                            .map(t -> new BudgetRules.Spend(t.categoryCode(), t.direction(), t.amountPaise()))
                            .toList()).values());
            UnplannedExpense.Result r = UnplannedExpense.rebalance(allocation, Money.ofPaise(amountPaise),
                    totalBudget, totalSpent, cycle, today);
            List<Line> absorbed = r.absorbed().stream()
                    .map(a -> new Line(a.step().name(), a.ref(), a.amount().paise())).toList();
            return new Preview(absorbed, absorbed.isEmpty() ? null : absorbed.get(0).step(),
                    r.uncovered().paise(), r.surplusAfter().paise(),
                    r.lines().stream().map(l -> new Line(l.step().name(), l.ref(), l.amount().paise())).toList(),
                    r.safeToSpendBefore().paise(), r.safeToSpendAfter().paise());
        });
    }
}
