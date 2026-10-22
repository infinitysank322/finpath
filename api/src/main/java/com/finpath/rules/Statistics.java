package com.finpath.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Statistics bundle (FR-26). Pure: ratios are percentages, net worth is a signed amount, the
 * debt-free cycle is the latest payoff cycle, and the streak is the trailing run of done cycles.
 */
public final class Statistics {

    /** Decimal places reported for percentages. */
    public static final int PERCENT_SCALE = 1;

    /** Percentages are rounded with the policy money rounding (half up). */
    private static final RoundingMode PERCENT_ROUNDING = MathPolicy.MONEY_ROUNDING;

    /** Percent points per whole ratio, as a decimal-point shift (ratio 0.25 becomes 25). */
    private static final int PERCENT_SHIFT = 2;

    private Statistics() {
    }

    /**
     * Surplus as a percent of income, to {@value #PERCENT_SCALE} place. A negative surplus gives a
     * negative rate. Zero income has no rate: returns null.
     */
    public static BigDecimal savingsRate(Money surplus, Money income) {
        return percent(surplus, income, false);
    }

    /**
     * Share of total outflow that is discretionary (wants plus waste), as a percent to
     * {@value #PERCENT_SCALE} place. Zero outflow has no share: returns null.
     */
    public static BigDecimal discretionaryShare(Money wantsPlusWaste, Money totalOutflow) {
        return percent(wantsPlusWaste, totalOutflow, true);
    }

    private static BigDecimal percent(Money part, Money whole, boolean partNonNegative) {
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(whole, "whole");
        if (whole.isNegative() || (partNonNegative && part.isNegative())) {
            throw new IllegalArgumentException("amounts must not be negative");
        }
        if (whole.paise() == 0) {
            return null;
        }
        return BigDecimal.valueOf(part.paise())
                .divide(BigDecimal.valueOf(whole.paise()), MathPolicy.CALC)
                .movePointRight(PERCENT_SHIFT)
                .setScale(PERCENT_SCALE, PERCENT_ROUNDING);
    }

    /**
     * Debt-free cycle: the latest 0-based payoff cycle across all debts. Empty when there are no
     * debts.
     */
    public static OptionalInt debtFreeCycle(Collection<Integer> payoffCycles) {
        Objects.requireNonNull(payoffCycles, "payoffCycles");
        OptionalInt latest = OptionalInt.empty();
        for (Integer c : payoffCycles) {
            Objects.requireNonNull(c, "payoffCycle");
            if (c < 0) {
                throw new IllegalArgumentException("payoff cycle must not be negative");
            }
            if (latest.isEmpty() || c > latest.getAsInt()) {
                latest = OptionalInt.of(c);
            }
        }
        return latest;
    }

    /** Debt-free cycle of a payoff plan. Empty when the plan has no debts. */
    public static OptionalInt debtFreeCycle(PayoffPlanner.Plan plan) {
        Objects.requireNonNull(plan, "plan");
        return debtFreeCycle(plan.payoffCycle().values());
    }

    /** Assets minus liabilities; negative when liabilities exceed assets. */
    public static Money netWorth(Money assets, Money liabilities) {
        Objects.requireNonNull(assets, "assets");
        Objects.requireNonNull(liabilities, "liabilities");
        if (assets.isNegative() || liabilities.isNegative()) {
            throw new IllegalArgumentException("assets and liabilities must not be negative");
        }
        return assets.minus(liabilities);
    }

    /**
     * Trailing run of completed cycles, input ordered oldest to newest. A missed latest cycle gives
     * 0 (not 1); an empty history gives 0.
     */
    public static int consistencyStreak(List<Boolean> doneByCycleOldestToNewest) {
        Objects.requireNonNull(doneByCycleOldestToNewest, "doneByCycleOldestToNewest");
        int streak = 0;
        for (int i = doneByCycleOldestToNewest.size() - 1; i >= 0; i--) {
            Boolean done = Objects.requireNonNull(doneByCycleOldestToNewest.get(i), "done flag");
            if (!done) {
                break;
            }
            streak++;
        }
        return streak;
    }
}
