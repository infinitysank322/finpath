package com.finpath.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Amortising (EMI) loan maths. Pure and deterministic: no clock, no I/O. Rates are annual
 * fractions (0.08 = 8%). Interest accrues monthly at rate/12 on the opening balance, rounded to
 * paise each instalment; the final instalment absorbs the rounding residue so the balance closes
 * at exactly zero. Not valid for bullet loans, which are interest-only.
 */
public final class AmortisingLoan {

    private static final BigDecimal MONTHS_PER_YEAR = BigDecimal.valueOf(12);

    private AmortisingLoan() {
    }

    /** One instalment. {@code payment = principal + interest}. */
    public record Instalment(int number, Money opening, Money interest, Money principal, Money payment,
            Money closing) {
    }

    /** Full repayment schedule; empty for a zero principal. */
    public record Schedule(List<Instalment> instalments) {

        public Schedule {
            instalments = Collections.unmodifiableList(new ArrayList<>(instalments));
        }

        public int size() {
            return instalments.size();
        }

        public Money totalInterest() {
            return sumInterest(0);
        }

        public Money totalPrincipal() {
            Money sum = Money.ZERO;
            for (Instalment i : instalments) {
                sum = sum.plus(i.principal());
            }
            return sum;
        }

        public Money totalPaid() {
            Money sum = Money.ZERO;
            for (Instalment i : instalments) {
                sum = sum.plus(i.payment());
            }
            return sum;
        }

        /** Interest in all instalments after the first {@code paid} ones. */
        public Money interestRemainingAfter(int paid) {
            return sumInterest(clampPaid(paid));
        }

        /** Principal still owed after the first {@code paid} instalments. */
        public Money balanceAfter(int paid) {
            int p = clampPaid(paid);
            if (instalments.isEmpty()) {
                return Money.ZERO;
            }
            return p == 0 ? instalments.get(0).opening() : instalments.get(p - 1).closing();
        }

        private int clampPaid(int paid) {
            if (paid < 0) {
                throw new IllegalArgumentException("paid must not be negative: " + paid);
            }
            return Math.min(paid, instalments.size());
        }

        private Money sumInterest(int from) {
            Money sum = Money.ZERO;
            for (int k = from; k < instalments.size(); k++) {
                sum = sum.plus(instalments.get(k).interest());
            }
            return sum;
        }
    }

    /** Monthly rate as a fraction at policy precision. */
    public static BigDecimal monthlyRate(BigDecimal annualRate) {
        Objects.requireNonNull(annualRate, "annualRate");
        if (annualRate.signum() < 0) {
            throw new IllegalArgumentException("annualRate must not be negative");
        }
        return annualRate.divide(MONTHS_PER_YEAR, MathPolicy.CALC);
    }

    /** Standard EMI, rounded to paise. Zero rate is principal / n; zero principal is zero. */
    public static Money emi(Money principal, BigDecimal annualRate, int tenureMonths) {
        requireInputs(principal, tenureMonths);
        BigDecimal r = monthlyRate(annualRate);
        if (principal.paise() == 0) {
            return Money.ZERO;
        }
        BigDecimal p = BigDecimal.valueOf(principal.paise());
        BigDecimal paise;
        if (r.signum() == 0) {
            paise = p.divide(BigDecimal.valueOf(tenureMonths), MathPolicy.CALC);
        } else {
            BigDecimal growth = BigDecimal.ONE.add(r).pow(tenureMonths, MathPolicy.CALC);
            paise = p.multiply(r, MathPolicy.CALC).multiply(growth, MathPolicy.CALC)
                    .divide(growth.subtract(BigDecimal.ONE), MathPolicy.CALC);
        }
        return new Money(paise.setScale(0, MathPolicy.MONEY_ROUNDING).longValueExact());
    }

    /** Schedule using the computed EMI over exactly {@code tenureMonths} instalments. */
    public static Schedule schedule(Money principal, BigDecimal annualRate, int tenureMonths) {
        return schedule(principal, annualRate, emi(principal, annualRate, tenureMonths), tenureMonths);
    }

    /**
     * Schedule for an explicit EMI (for example the lender's stated one), closed at the latest at
     * instalment {@code maxInstalments}, where the residue is settled. Ends early if the balance
     * clears sooner. Throws if the EMI cannot cover the first month's interest.
     */
    public static Schedule schedule(Money principal, BigDecimal annualRate, Money emi, int maxInstalments) {
        requireInputs(principal, maxInstalments);
        Objects.requireNonNull(emi, "emi");
        BigDecimal r = monthlyRate(annualRate);
        List<Instalment> out = new ArrayList<>();
        Money balance = principal;
        int n = 0;
        while (balance.paise() > 0) {
            n++;
            Money interest = balance.times(r);
            if (n == 1 && emi.paise() <= interest.paise() && maxInstalments > 1) {
                throw new IllegalArgumentException("EMI does not cover the monthly interest");
            }
            boolean last = n == maxInstalments || emi.paise() >= balance.plus(interest).paise();
            Money princ = last ? balance : emi.minus(interest);
            Money payment = princ.plus(interest);
            Money closing = balance.minus(princ);
            out.add(new Instalment(n, balance, interest, princ, payment, closing));
            balance = closing;
        }
        return new Schedule(out);
    }

    /** Instalments already due before the cycle containing {@code reference}, capped at the total. */
    public static int instalmentsPaid(Schedule schedule, LocalDate firstDue, LocalDate reference, int startDay) {
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(firstDue, "firstDue");
        Objects.requireNonNull(reference, "reference");
        int elapsed = CycleCalendar.cyclesBetween(firstDue, reference, startDay);
        return Math.max(0, Math.min(elapsed, schedule.size()));
    }

    /** Interest still to be paid as of the reference date; zero once the loan is closed. */
    public static Money interestRemaining(Schedule schedule, LocalDate firstDue, LocalDate reference,
            int startDay) {
        return schedule.interestRemainingAfter(instalmentsPaid(schedule, firstDue, reference, startDay));
    }

    /** Principal outstanding as of the reference date. */
    public static Money outstandingPrincipal(Schedule schedule, LocalDate firstDue, LocalDate reference,
            int startDay) {
        return schedule.balanceAfter(instalmentsPaid(schedule, firstDue, reference, startDay));
    }

    /** The cycle holding the last instalment; empty schedule (nothing owed) has none. */
    public static Optional<CycleCalendar.Cycle> payoffCycle(Schedule schedule, LocalDate firstDue,
            int startDay) {
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(firstDue, "firstDue");
        if (schedule.size() == 0) {
            return Optional.empty();
        }
        CycleCalendar.Cycle c = CycleCalendar.cycleFor(firstDue, startDay);
        for (int k = 1; k < schedule.size(); k++) {
            c = CycleCalendar.next(c, startDay);
        }
        return Optional.of(c);
    }

    /** True when every instalment falls before the cycle containing {@code reference}. */
    public static boolean isClosed(Schedule schedule, LocalDate firstDue, LocalDate reference, int startDay) {
        return instalmentsPaid(schedule, firstDue, reference, startDay) >= schedule.size();
    }

    private static void requireInputs(Money principal, int months) {
        Objects.requireNonNull(principal, "principal");
        if (principal.isNegative()) {
            throw new IllegalArgumentException("principal must not be negative");
        }
        if (months <= 0) {
            throw new IllegalArgumentException("tenure must be positive: " + months);
        }
    }
}
