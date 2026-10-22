package com.finpath.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Prepayment what-ifs for an amortising (EMI) loan. Pure and deterministic: every variant is
 * compared against the baseline schedule built by {@link AmortisingLoan}, so interest saved is
 * always {@code baseline interest - new interest} on the same schedule maths. Not valid for
 * bullet loans.
 */
public final class PrepaymentSimulator {

    private PrepaymentSimulator() {
    }

    /**
     * Baseline versus what-if. {@code newEmi} is the instalment used after the change; counts are
     * instalments from the original first instalment; the end date is the cycle of that
     * instalment, as {@link AmortisingLoan#payoffCycle} would give for a schedule of that length.
     */
    public record Outcome(Money newEmi, int baselineInstalments, int newInstalments,
            Money baselineInterest, Money newInterest) {

        public Money interestSaved() {
            return baselineInterest.minus(newInterest);
        }

        public int instalmentsSaved() {
            return baselineInstalments - newInstalments;
        }

        /** Cycle holding the last instalment of the what-if; empty when nothing is owed. */
        public Optional<CycleCalendar.Cycle> newPayoffCycle(LocalDate firstDue,
                int startDay) {
            return lastCycle(firstDue, startDay, newInstalments);
        }

        /** Cycle holding the last instalment of the baseline; empty when nothing is owed. */
        public Optional<CycleCalendar.Cycle> baselinePayoffCycle(LocalDate firstDue,
                int startDay) {
            return lastCycle(firstDue, startDay, baselineInstalments);
        }

        private static Optional<CycleCalendar.Cycle> lastCycle(LocalDate firstDue,
                int startDay, int count) {
            Objects.requireNonNull(firstDue, "firstDue");
            if (count == 0) {
                return Optional.empty();
            }
            CycleCalendar.Cycle c = CycleCalendar.cycleFor(firstDue, startDay);
            for (int k = 1; k < count; k++) {
                c = CycleCalendar.next(c, startDay);
            }
            return Optional.of(c);
        }
    }

    /** Inverse solve: the EMI that closes {@code principal} in exactly {@code months} instalments. */
    public static Money emiToClose(Money principal, BigDecimal annualRate, int months) {
        return AmortisingLoan.emi(principal, annualRate, months);
    }

    /**
     * Re-solve the loan to close in {@code closeMonths} instead of {@code tenureMonths}, both at
     * the computed EMI. Interest saved is interest over the tenure minus interest over the shorter
     * close.
     */
    public static Outcome closeIn(Money principal, BigDecimal annualRate, int tenureMonths, int closeMonths) {
        if (closeMonths <= 0 || closeMonths > tenureMonths) {
            throw new IllegalArgumentException(
                    "closeMonths must be between 1 and the tenure: " + closeMonths);
        }
        AmortisingLoan.Schedule base = AmortisingLoan.schedule(principal, annualRate, tenureMonths);
        AmortisingLoan.Schedule fast = AmortisingLoan.schedule(principal, annualRate, closeMonths);
        return new Outcome(emiToClose(principal, annualRate, closeMonths), base.size(), fast.size(),
                base.totalInterest(), fast.totalInterest());
    }

    /**
     * Lump sum paid just after instalment {@code afterInstalment} (0 = before the first), with the
     * EMI unchanged so the loan ends sooner. A lump at or above the balance closes the loan there.
     * {@code currentEmi} is the EMI actually paid; the baseline settles any residue at
     * {@code tenureMonths}.
     */
    public static Outcome lumpSum(Money principal, BigDecimal annualRate, Money currentEmi, int tenureMonths,
            int afterInstalment, Money lump) {
        Objects.requireNonNull(lump, "lump");
        if (lump.isNegative()) {
            throw new IllegalArgumentException("lump sum must not be negative");
        }
        AmortisingLoan.Schedule base = AmortisingLoan.schedule(principal, annualRate, currentEmi, tenureMonths);
        if (afterInstalment < 0 || afterInstalment > base.size()) {
            throw new IllegalArgumentException(
                    "afterInstalment must be within 0.." + base.size() + ": " + afterInstalment);
        }
        Money paidInterest = base.totalInterest().minus(base.interestRemainingAfter(afterInstalment));
        Money balance = base.balanceAfter(afterInstalment);
        Money after = lump.paise() >= balance.paise() ? Money.ZERO : balance.minus(lump);
        int remainingCap = Math.max(tenureMonths - afterInstalment, 1);
        AmortisingLoan.Schedule rest = AmortisingLoan.schedule(after, annualRate, currentEmi, remainingCap);
        return new Outcome(currentEmi, base.size(), afterInstalment + rest.size(), base.totalInterest(),
                paidInterest.plus(rest.totalInterest()));
    }

    /**
     * Pay a higher EMI from the start. {@code newEmi} must not be below {@code currentEmi}; an EMI
     * that cannot cover the first month's interest is rejected by the schedule.
     */
    public static Outcome increaseEmi(Money principal, BigDecimal annualRate, Money currentEmi,
            int tenureMonths, Money newEmi) {
        Objects.requireNonNull(newEmi, "newEmi");
        Objects.requireNonNull(currentEmi, "currentEmi");
        if (newEmi.paise() < currentEmi.paise()) {
            throw new IllegalArgumentException("newEmi must not be below the current EMI");
        }
        AmortisingLoan.Schedule base = AmortisingLoan.schedule(principal, annualRate, currentEmi, tenureMonths);
        AmortisingLoan.Schedule fast = AmortisingLoan.schedule(principal, annualRate, newEmi, tenureMonths);
        return new Outcome(newEmi, base.size(), fast.size(), base.totalInterest(), fast.totalInterest());
    }
}
