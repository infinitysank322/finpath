package com.finpath.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Budget rules for one cycle. Pure and deterministic: no clock, no I/O; the reference date is a
 * parameter. Thresholds come from {@link RuleConfig}; the comparison is exact (spent against
 * budget x threshold, no rounded percentage), so 79.9% never reaches the 80% alert.
 *
 * <p>Alert level is monotonic within a cycle: {@link #newAlert} returns an event only when the
 * level rises above what has already been fired, so crossing 100% in one jump yields a single
 * BREACH and no WARN, and re-evaluating the same spend yields nothing.
 */
public final class BudgetRules {

    private BudgetRules() {
    }

    /** Alert level, ordered by severity. */
    public enum Level { OK, WARN, BREACH }

    /** Consumption of one category budget. */
    public record Status(Money budget, Money spent, Money remaining, BigDecimal usedFraction, Level level) {
    }

    /** Level reached by {@code spent} against {@code budget}. A zero budget is BREACH once anything is spent. */
    public static Level levelFor(Money budget, Money spent, RuleConfig config) {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(spent, "spent");
        Objects.requireNonNull(config, "config");
        requireNonNegative(budget, "budget");
        if (budget.paise() == 0) {
            return spent.paise() > 0 ? Level.BREACH : Level.OK;
        }
        BigDecimal spentPaise = BigDecimal.valueOf(spent.paise());
        BigDecimal budgetPaise = BigDecimal.valueOf(budget.paise());
        if (spentPaise.compareTo(budgetPaise.multiply(config.budgetAlertLimit())) >= 0) {
            return Level.BREACH;
        }
        if (spentPaise.compareTo(budgetPaise.multiply(config.budgetAlertWarn())) >= 0) {
            return Level.WARN;
        }
        return Level.OK;
    }

    /** Full status: remaining may be negative once overspent; the fraction is null for a zero budget. */
    public static Status status(Money budget, Money spent, RuleConfig config) {
        Objects.requireNonNull(budget, "budget");
        Objects.requireNonNull(spent, "spent");
        Level level = levelFor(budget, spent, config);
        BigDecimal fraction = budget.paise() == 0
                ? null
                : BigDecimal.valueOf(spent.paise())
                        .divide(BigDecimal.valueOf(budget.paise()), MathPolicy.RATE_SCALE, MathPolicy.MONEY_ROUNDING);
        return new Status(budget, spent, budget.minus(spent), fraction, level);
    }

    /**
     * The alert to raise now, if any: the current level when it is above {@code alreadyFired}
     * (use {@link Level#OK} when nothing has fired this cycle), otherwise empty. Never returns OK.
     */
    public static Optional<Level> newAlert(Level alreadyFired, Level current) {
        Objects.requireNonNull(alreadyFired, "alreadyFired");
        Objects.requireNonNull(current, "current");
        return current.compareTo(alreadyFired) > 0 ? Optional.of(current) : Optional.empty();
    }

    /**
     * Money that can be spent per remaining day, today included: (totalBudget - totalSpent) divided
     * by days left, rounded DOWN to whole paise so the figure never overstates what is safe. Zero
     * when the budget is used up or overspent.
     */
    public static Money safeToSpendPerDay(Money totalBudget, Money totalSpent, CycleCalendar.Cycle cycle,
            LocalDate today) {
        Objects.requireNonNull(totalBudget, "totalBudget");
        Objects.requireNonNull(totalSpent, "totalSpent");
        Objects.requireNonNull(cycle, "cycle");
        Objects.requireNonNull(today, "today");
        requireNonNegative(totalBudget, "totalBudget");
        requireNonNegative(totalSpent, "totalSpent");
        long remaining = totalBudget.paise() - totalSpent.paise();
        if (remaining <= 0) {
            return Money.ZERO;
        }
        int daysLeft = CycleCalendar.daysLeft(cycle, today);
        return Money.ofPaise(BigDecimal.valueOf(remaining)
                .divide(BigDecimal.valueOf(daysLeft), 0, RoundingMode.DOWN)
                .longValueExact());
    }

    /** One transaction reduced to what spend needs. */
    public record Spend(String categoryCode, TransactionHash.Direction direction, long amountPaise) {
    }

    /**
     * Spend per category code: OUT rows only, summed with {@code Math.addExact}; IN rows are ignored.
     * A category with no OUT rows is absent (callers treat that as {@link Money#ZERO}).
     */
    public static Map<String, Money> spentByCategory(List<Spend> rows) {
        Objects.requireNonNull(rows, "rows");
        Map<String, Money> totals = new TreeMap<>();
        for (Spend row : rows) {
            Objects.requireNonNull(row, "row");
            Objects.requireNonNull(row.categoryCode(), "categoryCode");
            Objects.requireNonNull(row.direction(), "direction");
            if (row.direction() != TransactionHash.Direction.OUT) {
                continue;
            }
            totals.merge(row.categoryCode(), Money.ofPaise(row.amountPaise()),
                    (a, b) -> Money.ofPaise(Math.addExact(a.paise(), b.paise())));
        }
        return totals;
    }

    /** Exact total of the amounts ({@code Math.addExact}); zero for an empty collection. */
    public static Money sum(java.util.Collection<Money> amounts) {
        Objects.requireNonNull(amounts, "amounts");
        long total = 0L;
        for (Money m : amounts) {
            Objects.requireNonNull(m, "amount");
            total = Math.addExact(total, m.paise());
        }
        return Money.ofPaise(total);
    }

    private static void requireNonNegative(Money m, String name) {
        if (m.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }
}
