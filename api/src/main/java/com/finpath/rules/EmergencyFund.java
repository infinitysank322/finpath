package com.finpath.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Emergency-fund sizing (FR-20). Pure: the target is a whole number of months of actual monthly
 * outflow, and cover is current savings divided by that outflow.
 */
public final class EmergencyFund {

    /** Decimal places reported for months covered. */
    public static final int MONTHS_SCALE = 2;

    private EmergencyFund() {
    }

    /** Fund needed to cover {@code months} months of outflow. */
    public static Money target(Money monthlyOutflow, int months) {
        Objects.requireNonNull(monthlyOutflow, "monthlyOutflow");
        if (monthlyOutflow.isNegative()) {
            throw new IllegalArgumentException("monthlyOutflow must not be negative");
        }
        if (months <= 0) {
            throw new IllegalArgumentException("months must be positive");
        }
        return Money.ofPaise(Math.multiplyExact(monthlyOutflow.paise(), (long) months));
    }

    /**
     * Months of outflow the current fund covers, to {@value #MONTHS_SCALE} places, rounded DOWN so
     * cover is never overstated. Zero outflow means nothing to cover: returns null.
     */
    public static BigDecimal monthsCovered(Money current, Money monthlyOutflow) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(monthlyOutflow, "monthlyOutflow");
        if (current.isNegative() || monthlyOutflow.isNegative()) {
            throw new IllegalArgumentException("amounts must not be negative");
        }
        if (monthlyOutflow.paise() == 0) {
            return null;
        }
        return BigDecimal.valueOf(current.paise())
                .divide(BigDecimal.valueOf(monthlyOutflow.paise()), MONTHS_SCALE, RoundingMode.DOWN);
    }

    /** How far {@code current} is below {@code target}; zero once the target is met. */
    public static Money shortfall(Money current, Money target) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(target, "target");
        if (current.isNegative() || target.isNegative()) {
            throw new IllegalArgumentException("amounts must not be negative");
        }
        return Money.ofPaise(Math.max(0L, target.paise() - current.paise()));
    }

    /**
     * Monthly outflow to size from: what was actually spent last cycle, but never less than the
     * commitments (EMIs and recurring bills) due now, so a thin history cannot under-size the fund.
     */
    public static Money monthlyOutflow(Money actualLastCycle, Money committed) {
        Objects.requireNonNull(actualLastCycle, "actualLastCycle");
        Objects.requireNonNull(committed, "committed");
        if (actualLastCycle.isNegative() || committed.isNegative()) {
            throw new IllegalArgumentException("amounts must not be negative");
        }
        return actualLastCycle.paise() >= committed.paise() ? actualLastCycle : committed;
    }
}
