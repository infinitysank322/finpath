package com.finpath.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Emergency-fund sizing from actual monthly outflow (FR-20). Pure and deterministic. Targets are
 * the outflow times the one- and three-month multiples, exact in paise; months covered is the
 * current fund divided by outflow, rounded {@link RoundingMode#HALF_UP} to {@link #MONTHS_SCALE}
 * places. A zero outflow has no meaningful cover, so months covered is zero.
 */
public final class EmergencyFund {

    /** Decimal places of the months-covered statistic. */
    public static final int MONTHS_SCALE = 2;

    private static final int ONE_MONTH = 1;
    private static final int THREE_MONTHS = 3;

    private EmergencyFund() {
    }

    /**
     * @param oneMonth one month of outflow
     * @param threeMonth three months of outflow
     * @param monthsCovered how many months of outflow the current fund covers
     */
    public record Result(Money oneMonth, Money threeMonth, BigDecimal monthsCovered) {
    }

    /**
     * @param monthlyOutflow actual average monthly outflow
     * @param current emergency fund held today
     * @throws IllegalArgumentException for a negative amount
     */
    public static Result evaluate(Money monthlyOutflow, Money current) {
        Objects.requireNonNull(monthlyOutflow, "monthlyOutflow");
        Objects.requireNonNull(current, "current");
        if (monthlyOutflow.isNegative()) {
            throw new IllegalArgumentException("monthlyOutflow must not be negative");
        }
        if (current.isNegative()) {
            throw new IllegalArgumentException("current must not be negative");
        }
        BigDecimal covered = monthlyOutflow.paise() == 0L
                ? BigDecimal.ZERO.setScale(MONTHS_SCALE)
                : BigDecimal.valueOf(current.paise()).divide(BigDecimal.valueOf(monthlyOutflow.paise()),
                        MONTHS_SCALE, MathPolicy.MONEY_ROUNDING);
        return new Result(target(monthlyOutflow, ONE_MONTH), target(monthlyOutflow, THREE_MONTHS), covered);
    }

    private static Money target(Money outflow, int months) {
        return Money.ofPaise(Math.multiplyExact(outflow.paise(), (long) months));
    }
}
