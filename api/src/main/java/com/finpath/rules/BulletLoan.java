package com.finpath.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Interest-only (bullet) loan maths and the card-EMI cost basis. Pure and deterministic: no
 * clock, no I/O; the reference date is always a parameter. Rates are annual fractions.
 */
public final class BulletLoan {

    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);
    /** Bisection steps for the card-EMI monthly IRR; 200 halvings far exceed DECIMAL128 precision. */
    private static final int IRR_ITERATIONS = 200;
    /** Upper bracket for the monthly IRR: 100% a month, far above any real card EMI. */
    private static final BigDecimal IRR_MAX_MONTHLY = BigDecimal.ONE;

    private BulletLoan() {
    }

    /**
     * Effective annual rate as a fraction: monthly interest x 12 / principal, at
     * {@link MathPolicy#RATE_SCALE}. A zero principal owes nothing and yields zero.
     */
    public static BigDecimal effectiveAnnualRate(Loan.Bullet loan) {
        Objects.requireNonNull(loan, "loan");
        if (loan.principal().paise() == 0) {
            return BigDecimal.ZERO.setScale(MathPolicy.RATE_SCALE);
        }
        return BigDecimal.valueOf(loan.monthlyInterest().paise())
                .multiply(MONTHS_PER_YEAR)
                .divide(BigDecimal.valueOf(loan.principal().paise()), MathPolicy.RATE_SCALE,
                        MathPolicy.MONEY_ROUNDING);
    }

    /**
     * Monthly interest on an interest-only loan: {@code principal * annualRate / 12}, rounded
     * once, HALF_UP, to paise. The inverse of {@link #effectiveAnnualRate(Loan.Bullet)}. Rejects
     * a negative principal or rate.
     */
    public static Money monthlyInterest(Money principal, BigDecimal annualRate) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(annualRate, "annualRate");
        if (principal.isNegative()) {
            throw new IllegalArgumentException("principal must not be negative");
        }
        if (annualRate.signum() < 0) {
            throw new IllegalArgumentException("rate must not be negative");
        }
        BigDecimal paise = BigDecimal.valueOf(principal.paise())
                .multiply(annualRate)
                .divide(MONTHS_PER_YEAR, 0, MathPolicy.MONEY_ROUNDING);
        return Money.ofPaise(paise.longValueExact());
    }

    /**
     * Interest still to pay from the cycle containing {@code reference} up to and including the
     * cycle of the due date: one monthly interest per cycle. Zero once the due date's cycle has
     * passed (past due, nothing further accrues in this model) or for a zero principal.
     */
    public static Money interestToDueDate(Loan.Bullet loan, LocalDate reference, int startDay) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(reference, "reference");
        if (loan.principal().paise() == 0) {
            return Money.ZERO;
        }
        int cycles = CycleCalendar.cyclesBetween(reference, loan.dueDate(), startDay);
        if (cycles < 0) {
            return Money.ZERO;
        }
        return loan.monthlyInterest().times(BigDecimal.valueOf(cycles + 1L));
    }

    /** True when the due date's cycle is before the cycle containing {@code reference}. */
    public static boolean isPastDue(Loan.Bullet loan, LocalDate reference, int startDay) {
        Objects.requireNonNull(loan, "loan");
        Objects.requireNonNull(reference, "reference");
        return CycleCalendar.cyclesBetween(reference, loan.dueDate(), startDay) < 0;
    }

    /** Total cost of a card EMI: all instalments plus the upfront fee, minus the amount financed. */
    public static Money cardEmiTotalCost(Loan.CardEmi loan) {
        Objects.requireNonNull(loan, "loan");
        Money paid = loan.instalment().times(BigDecimal.valueOf(loan.tenureMonths()));
        return paid.plus(loan.upfrontFee()).minus(loan.principal());
    }

    /**
     * Card-EMI effective annual rate, a nominal rate comparable with the bullet figure (monthly
     * rate x 12). Cost basis: the borrower really receives {@code principal - upfrontFee} (the fee
     * is paid at conversion, so it is not financed) and repays {@code instalment} each month for
     * {@code tenureMonths}. The monthly rate is the internal rate of return of those flows, found
     * by bisection. A "0% EMI" with a fee therefore shows its true cost. Zero if the principal is
     * zero or the cash flows carry no cost (instalments total no more than the net received);
     * throws if the fee swallows the whole principal while instalments remain due.
     */
    public static BigDecimal cardEmiEffectiveAnnualRate(Loan.CardEmi loan) {
        Objects.requireNonNull(loan, "loan");
        if (loan.principal().paise() == 0) {
            return BigDecimal.ZERO.setScale(MathPolicy.RATE_SCALE);
        }
        BigDecimal net = BigDecimal.valueOf(loan.principal().minus(loan.upfrontFee()).paise());
        BigDecimal pmt = BigDecimal.valueOf(loan.instalment().paise());
        int n = loan.tenureMonths();
        if (net.signum() <= 0) {
            throw new IllegalArgumentException("upfront fee must be less than the principal");
        }
        if (pmt.multiply(BigDecimal.valueOf(n)).compareTo(net) <= 0) {
            return BigDecimal.ZERO.setScale(MathPolicy.RATE_SCALE);
        }
        BigDecimal lo = BigDecimal.ZERO;
        BigDecimal hi = IRR_MAX_MONTHLY;
        for (int k = 0; k < IRR_ITERATIONS; k++) {
            BigDecimal mid = lo.add(hi).divide(TWO, MathPolicy.CALC);
            if (presentValue(pmt, mid, n).compareTo(net) > 0) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return lo.add(hi).divide(TWO, MathPolicy.CALC).multiply(MONTHS_PER_YEAR)
                .setScale(MathPolicy.RATE_SCALE, MathPolicy.MONEY_ROUNDING);
    }

    /** PV of {@code n} level payments at monthly rate r (r &gt; 0). */
    private static BigDecimal presentValue(BigDecimal pmt, BigDecimal r, int n) {
        BigDecimal growth = BigDecimal.ONE.add(r).pow(n, MathPolicy.CALC);
        BigDecimal factor = BigDecimal.ONE.subtract(BigDecimal.ONE.divide(growth, MathPolicy.CALC))
                .divide(r, MathPolicy.CALC);
        return pmt.multiply(factor, MathPolicy.CALC);
    }
}
