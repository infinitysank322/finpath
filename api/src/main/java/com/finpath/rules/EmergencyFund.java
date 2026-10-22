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
}
