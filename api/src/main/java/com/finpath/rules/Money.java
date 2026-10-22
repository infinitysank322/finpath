package com.finpath.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Paise (long) value with the BigDecimal boundary. Keep calculations in BigDecimal
 * and convert at the edges; amounts here are exact paise.
 */
public record Money(long paise) {

    public static final Money ZERO = new Money(0L);

    private static final BigDecimal PAISE_PER_RUPEE = BigDecimal.valueOf(100);

    public static Money ofPaise(long paise) {
        return new Money(paise);
    }

    /** Rounds to whole paise with the policy rounding mode. */
    public static Money ofRupees(BigDecimal rupees) {
        Objects.requireNonNull(rupees, "rupees");
        return new Money(rupees.multiply(PAISE_PER_RUPEE)
                .setScale(0, MathPolicy.MONEY_ROUNDING)
                .longValueExact());
    }

    /** Exact rupee value at scale 2. */
    public BigDecimal toRupees() {
        return BigDecimal.valueOf(paise, MathPolicy.RUPEE_SCALE);
    }

    public Money plus(Money other) {
        return new Money(Math.addExact(paise, other.paise));
    }

    public Money minus(Money other) {
        return new Money(Math.subtractExact(paise, other.paise));
    }

    /** Multiplies and rounds to paise with the policy rounding mode. */
    public Money times(BigDecimal factor) {
        Objects.requireNonNull(factor, "factor");
        return new Money(BigDecimal.valueOf(paise)
                .multiply(factor)
                .setScale(0, MathPolicy.MONEY_ROUNDING)
                .longValueExact());
    }

    /** Always throws: callers must state scale and rounding. */
    public BigDecimal divide(BigDecimal divisor) {
        throw new UnsupportedOperationException(
                "divide requires an explicit scale and RoundingMode");
    }

    /** Returns this amount in rupees divided by the divisor, at the given scale. */
    public BigDecimal divide(BigDecimal divisor, int scale, RoundingMode mode) {
        Objects.requireNonNull(divisor, "divisor");
        Objects.requireNonNull(mode, "mode");
        return toRupees().divide(divisor, scale, mode);
    }

    public boolean isNegative() {
        return paise < 0;
    }
}
