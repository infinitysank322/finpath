package com.finpath.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Step-up SIP (FR-21). Pure: contributions are made at the end of each month and compound monthly at
 * {@code annualReturn / 12}; the monthly amount steps up by {@code stepUp} at the start of every
 * 12-month year. Closed form, no iteration over months.
 */
public final class StepUpSip {

    private static final int MONTHS_PER_YEAR = 12;
    private static final BigDecimal MONTHS = BigDecimal.valueOf(MONTHS_PER_YEAR);

    private StepUpSip() {
    }

    /** Value after {@code months} of contributions starting at {@code firstMonthly}. */
    public static Money futureValue(Money firstMonthly, BigDecimal annualReturn, BigDecimal stepUp, int months) {
        Objects.requireNonNull(firstMonthly, "firstMonthly");
        if (firstMonthly.isNegative()) {
            throw new IllegalArgumentException("firstMonthly must not be negative");
        }
        BigDecimal factor = unitFactor(annualReturn, stepUp, months);
        return firstMonthly.times(factor);
    }

    /**
     * Smallest first monthly contribution (rounded up to the paise) whose future value reaches
     * {@code target} after {@code months}. Zero if the target is zero.
     */
    public static Money requiredFirstMonthly(Money target, BigDecimal annualReturn, BigDecimal stepUp,
                                             int months) {
        Objects.requireNonNull(target, "target");
        if (target.isNegative()) {
            throw new IllegalArgumentException("target must not be negative");
        }
        if (months <= 0) {
            throw new IllegalArgumentException("months must be positive");
        }
        BigDecimal factor = unitFactor(annualReturn, stepUp, months);
        BigDecimal paise = new BigDecimal(target.paise()).divide(factor, MathPolicy.CALC)
                .setScale(0, RoundingMode.CEILING);
        if (paise.toBigInteger().bitLength() >= Long.SIZE) {
            throw new IllegalArgumentException("required contribution exceeds the supported range");
        }
        Money candidate = Money.ofPaise(paise.longValueExact());
        // futureValue rounds to paise, so step up until the guarantee holds exactly
        while (futureValue(candidate, annualReturn, stepUp, months).paise() < target.paise()) {
            candidate = Money.ofPaise(candidate.paise() + 1);
        }
        return candidate;
    }

    /** Future value of a first monthly contribution of exactly 1 (paise-for-paise multiplier). */
    private static BigDecimal unitFactor(BigDecimal annualReturn, BigDecimal stepUp, int months) {
        Objects.requireNonNull(annualReturn, "annualReturn");
        Objects.requireNonNull(stepUp, "stepUp");
        if (months < 0) {
            throw new IllegalArgumentException("months must not be negative");
        }
        if (annualReturn.signum() < 0 || stepUp.signum() < 0) {
            throw new IllegalArgumentException("rates must not be negative");
        }
        if (months == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal i = annualReturn.divide(MONTHS, MathPolicy.CALC);
        BigDecimal growth = BigDecimal.ONE.add(i);
        BigDecimal g = BigDecimal.ONE.add(stepUp);
        int years = months / MONTHS_PER_YEAR;
        int rest = months % MONTHS_PER_YEAR;

        BigDecimal r = growth.pow(MONTHS_PER_YEAR, MathPolicy.CALC);
        BigDecimal yearAnnuity = annuity(growth, i, MONTHS_PER_YEAR);

        // sum over full years k of g^k * r^(years-1-k), then grown through the partial year
        BigDecimal fullYears;
        if (years == 0) {
            fullYears = BigDecimal.ZERO;
        } else if (r.compareTo(g) == 0) {
            fullYears = BigDecimal.valueOf(years).multiply(r.pow(years - 1, MathPolicy.CALC), MathPolicy.CALC);
        } else {
            fullYears = r.pow(years, MathPolicy.CALC).subtract(g.pow(years, MathPolicy.CALC))
                    .divide(r.subtract(g), MathPolicy.CALC);
        }
        BigDecimal total = yearAnnuity.multiply(fullYears, MathPolicy.CALC)
                .multiply(growth.pow(rest, MathPolicy.CALC), MathPolicy.CALC);
        if (rest > 0) {
            total = total.add(g.pow(years, MathPolicy.CALC).multiply(annuity(growth, i, rest), MathPolicy.CALC));
        }
        return total;
    }

    /** ((1+i)^n - 1) / i, or n when the rate is zero. */
    private static BigDecimal annuity(BigDecimal growth, BigDecimal i, int n) {
        if (i.signum() == 0) {
            return BigDecimal.valueOf(n);
        }
        return growth.pow(n, MathPolicy.CALC).subtract(BigDecimal.ONE).divide(i, MathPolicy.CALC);
    }
}
