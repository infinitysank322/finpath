package com.finpath.rules;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Mid-cycle unplanned expense rebalance (FR-07). Pure. The expense is funded from the cycle's
 * allocations, trimming the lowest-priority waterfall step first (long-term SIP, then prepayment,
 * habit SIP, safe goals, emergency-fund target, high-interest debt, emergency-fund first month), and
 * it also counts as spending, so safe-to-spend per day falls. Whatever the allocations cannot cover
 * is reported as {@code uncovered}. The first {@code absorbed} entry is the primary absorbing step.
 * Both effects are deliberate (PLAN T24: the spend lowers safe-to-spend and names the absorbing step).
 * Step priority is the declaration order of {@link WaterfallAllocator.Step}. The rebalanced lines sum exactly to the reduced surplus.
 */
public final class UnplannedExpense {

    private UnplannedExpense() {
    }

    /** What one step gave up. */
    public record Absorbed(WaterfallAllocator.Step step, String ref, Money amount) {
    }

    public record Result(List<WaterfallAllocator.Line> lines, Money surplusAfter, List<Absorbed> absorbed,
                         Money uncovered, Money safeToSpendBefore, Money safeToSpendAfter) {
        public Result {
            lines = Collections.unmodifiableList(new ArrayList<>(lines));
            absorbed = Collections.unmodifiableList(new ArrayList<>(absorbed));
        }
    }

    public static Result rebalance(WaterfallAllocator.Result plan, Money expense, Money totalBudget,
                                   Money totalSpent, CycleCalendar.Cycle cycle, LocalDate today) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(expense, "expense");
        if (expense.isNegative()) {
            throw new IllegalArgumentException("expense must not be negative");
        }
        Money before = BudgetRules.safeToSpendPerDay(totalBudget, totalSpent, cycle, today);
        Money after = BudgetRules.safeToSpendPerDay(totalBudget, totalSpent.plus(expense), cycle, today);

        // priority order regardless of how the caller ordered the lines (stable within a step)
        List<WaterfallAllocator.Line> lines = new ArrayList<>(plan.lines());
        lines.sort(java.util.Comparator.comparing(l -> l.step().ordinal()));
        List<Absorbed> absorbed = new ArrayList<>();
        long left = expense.paise();
        // walk from the lowest priority (latest step) upwards
        for (int i = lines.size() - 1; i >= 0 && left > 0; i--) {
            WaterfallAllocator.Line l = lines.get(i);
            long take = Math.min(left, l.amount().paise());
            if (take == 0) {
                continue;
            }
            left -= take;
            absorbed.add(new Absorbed(l.step(), l.ref(), Money.ofPaise(take)));
            long remaining = l.amount().paise() - take;
            if (remaining == 0) {
                lines.remove(i);
            } else {
                lines.set(i, new WaterfallAllocator.Line(l.step(), l.ref(), Money.ofPaise(remaining)));
            }
        }
        long surplusAfter = Math.max(0L, plan.surplus().paise() - (expense.paise() - left));
        return new Result(lines, Money.ofPaise(surplusAfter), absorbed, Money.ofPaise(left), before, after);
    }
}
