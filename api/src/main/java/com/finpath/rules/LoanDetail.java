package com.finpath.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Single facade for the loan-detail view (FR-13). Pure and deterministic: no clock, no I/O; the
 * reference date and cycle start day are parameters. A caller (service, controller) passes plain
 * stored values in and renders the record out, doing no arithmetic of its own.
 *
 * <p>The STATED instalment is the cash-flow source of truth: the schedule, interest remaining,
 * total payable and payoff cycle are all derived from it, never from the computed EMI. The computed
 * EMI is returned separately, only for a labelled comparison. When no EMI is stated (EMI and
 * bullet types), the derived figure is used and {@code emiDerived} is true.
 *
 * <p>The first remaining instalment falls in the cycle containing {@code reference}, matching
 * {@link BulletLoan#interestToDueDate}. Per type: EMI is amortising ({@link AmortisingLoan}),
 * bullet is interest-only with the principal at the due date ({@link BulletLoan}), card EMI is a
 * level stated instalment whose cost is spread straight-line over the remaining instalments.
 */
public final class LoanDetail {

    /** Upper bound on remaining instalments, which bounds the schedule (and response) size. */
    public static final int MAX_EMIS_LEFT = 600;

    /** Furthest a bullet due date may lie beyond the reference date, in years. */
    public static final int MAX_BULLET_YEARS = 50;

    /** Rounding drift allowed per instalment before a larger final payment counts as a balloon. */
    private static final long ROUNDING_PAISE_PER_INSTALMENT = 1L;

    private LoanDetail() {
    }

    /** True if {@code emisLeft} is positive and within {@link #MAX_EMIS_LEFT}. */
    public static boolean tenureWithinLimit(long emisLeft) {
        return emisLeft > 0 && emisLeft <= MAX_EMIS_LEFT;
    }

    /** True if {@code due} is no later than {@link #MAX_BULLET_YEARS} years after {@code reference}. */
    public static boolean dueWithinHorizon(LocalDate due, LocalDate reference) {
        return !due.isAfter(reference.plusYears(MAX_BULLET_YEARS));
    }

    /**
     * Result of {@link #compute}.
     *
     * @param emiUsed the instalment every cash-flow figure is based on (stated, else derived);
     *        for a bullet loan the monthly interest
     * @param emiDerived true when no EMI was stated and {@code emiUsed} is the computed figure
     * @param computedEmi the rules-derived EMI from principal, rate and remaining tenure (monthly
     *        interest for bullet); empty for card EMI, which has no derivation
     * @param effectiveAnnualRate annual fraction at {@link MathPolicy#RATE_SCALE}
     * @param interestRemaining interest still to pay, from the stated/used instalment
     * @param totalRemainingPayable sum of all remaining payments in {@code instalments}
     * @param instalmentsRemaining number of remaining payments (zero once closed or past due)
     * @param payoffCycle cycle of the final payment (due-date cycle for bullet); empty if closed
     * @param closed nothing is owed (zero principal)
     * @param pastDue bullet only: due-date cycle is before the reference cycle
     * @param balloonFinalPayment EMI only: the final payment exceeds {@code emiUsed} because the stated
     *        instalment does not clear the loan within {@code emisLeft}
     * @param instalments remaining payments in order; first is in the reference cycle
     */
    public record Result(LoanType type, Money emiUsed, boolean emiDerived, Optional<Money> computedEmi,
            BigDecimal effectiveAnnualRate, Money interestRemaining, Money totalRemainingPayable,
            int instalmentsRemaining, Optional<CycleCalendar.Cycle> payoffCycle, boolean closed,
            boolean pastDue, boolean balloonFinalPayment, List<AmortisingLoan.Instalment> instalments) {

        public Result {
            instalments = Collections.unmodifiableList(new ArrayList<>(instalments));
        }
    }

    /**
     * @param type loan shape; selects the maths
     * @param principal principal outstanding (not negative)
     * @param annualRate annual fraction (0.08 = 8%), not negative
     * @param statedEmi the lender's/user's instalment, or null to derive (not allowed for card EMI)
     * @param emisLeft remaining instalments; required (positive) for EMI and card EMI, ignored for bullet
     * @param bulletDueDate required for bullet, ignored otherwise
     * @param upfrontFee card EMI fee paid at conversion; null is zero; ignored for other types
     * @param reference pinned reference date
     * @param startDay cycle start day
     * @throws IllegalArgumentException on missing or invalid inputs, or a stated EMI that does not
     *         cover the first month's interest
     */
    public static Result compute(LoanType type, Money principal, BigDecimal annualRate, Money statedEmi,
            Integer emisLeft, LocalDate bulletDueDate, Money upfrontFee, LocalDate reference, int startDay) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(annualRate, "annualRate");
        Objects.requireNonNull(reference, "reference");
        if (principal.isNegative()) {
            throw new IllegalArgumentException("principal must not be negative");
        }
        if (annualRate.signum() < 0) {
            throw new IllegalArgumentException("rate must not be negative");
        }
        if (statedEmi != null && statedEmi.paise() <= 0) {
            throw new IllegalArgumentException("stated EMI must be positive");
        }
        if (type == LoanType.BULLET && bulletDueDate != null && !dueWithinHorizon(bulletDueDate, reference)) {
            throw new IllegalArgumentException("bullet due date beyond " + MAX_BULLET_YEARS + " years");
        }
        return switch (type) {
            case EMI -> emi(principal, annualRate, statedEmi, requireTenure(emisLeft), reference, startDay);
            case BULLET -> bullet(principal, annualRate, statedEmi,
                    Objects.requireNonNull(bulletDueDate, "bulletDueDate"), reference, startDay);
            case CARD_EMI -> cardEmi(principal, statedEmi, requireTenure(emisLeft),
                    upfrontFee == null ? Money.ZERO : upfrontFee, reference, startDay);
        };
    }

    private static int requireTenure(Integer emisLeft) {
        if (emisLeft == null || !tenureWithinLimit(emisLeft)) {
            throw new IllegalArgumentException("emisLeft must be between 1 and " + MAX_EMIS_LEFT);
        }
        return emisLeft;
    }

    private static Result emi(Money principal, BigDecimal rate, Money stated, int emisLeft, LocalDate reference,
            int startDay) {
        Money computed = AmortisingLoan.emi(principal, rate, emisLeft);
        boolean derived = stated == null;
        Money used = derived ? computed : stated;
        AmortisingLoan.Schedule schedule = AmortisingLoan.schedule(principal, rate, used, emisLeft);
        CycleCalendar.Cycle payoff = payoffCycleOf(schedule.size(), reference, startDay);
        // The effective annual rate of an amortising loan is its stated rate.
        return new Result(LoanType.EMI, used, derived, Optional.of(computed),
                rate.setScale(MathPolicy.RATE_SCALE, MathPolicy.MONEY_ROUNDING), schedule.totalInterest(),
                schedule.totalPaid(), schedule.size(), Optional.ofNullable(payoff), schedule.size() == 0, false,
                balloon(schedule.instalments(), used), schedule.instalments());
    }

    private static Result bullet(Money principal, BigDecimal rate, Money stated, LocalDate due,
            LocalDate reference, int startDay) {
        Money computed = BulletLoan.monthlyInterest(principal, rate);
        boolean derived = stated == null;
        Money used = derived ? computed : stated;
        Loan.Bullet loan = new Loan.Bullet(principal, used, due);
        BigDecimal effective = BulletLoan.effectiveAnnualRate(loan);
        Money interest = BulletLoan.interestToDueDate(loan, reference, startDay);
        boolean pastDue = BulletLoan.isPastDue(loan, reference, startDay);
        boolean closed = principal.paise() == 0;
        List<AmortisingLoan.Instalment> out = new ArrayList<>();
        int n = 0;
        if (!closed && !pastDue) {
            n = CycleCalendar.cyclesBetween(reference, due, startDay) + 1;
            for (int k = 1; k <= n; k++) {
                Money repaid = k == n ? principal : Money.ZERO;
                out.add(new AmortisingLoan.Instalment(k, principal, used, repaid, used.plus(repaid),
                        principal.minus(repaid)));
            }
        }
        Optional<CycleCalendar.Cycle> payoff = closed ? Optional.empty()
                : Optional.of(CycleCalendar.cycleFor(due, startDay));
        // Past due: no further interest accrues here, but the principal is still owed.
        Money total = closed ? Money.ZERO : interest.plus(principal);
        return new Result(LoanType.BULLET, used, derived, Optional.of(computed), effective, interest, total, n,
                payoff, closed, pastDue, false, out);
    }

    private static Result cardEmi(Money principal, Money stated, int emisLeft, Money fee, LocalDate reference,
            int startDay) {
        if (stated == null) {
            throw new IllegalArgumentException("card EMI requires a stated instalment");
        }
        Loan.CardEmi loan = new Loan.CardEmi(principal, fee, stated, emisLeft);
        BigDecimal effective = BulletLoan.cardEmiEffectiveAnnualRate(loan);
        if (principal.paise() == 0) {
            return new Result(LoanType.CARD_EMI, stated, false, Optional.empty(), effective, Money.ZERO,
                    Money.ZERO, 0, Optional.empty(), true, false, false, List.of());
        }
        Money total = stated.times(BigDecimal.valueOf(emisLeft));
        if (total.paise() < principal.paise()) {
            throw new IllegalArgumentException("instalments do not repay the principal outstanding");
        }
        // Principal repaid straight-line (cumulative rounding, last instalment settles the residue);
        // the instalment itself stays the stated figure, the remainder of each payment is cost.
        List<AmortisingLoan.Instalment> out = new ArrayList<>();
        BigDecimal p = BigDecimal.valueOf(principal.paise());
        BigDecimal n = BigDecimal.valueOf(emisLeft);
        Money balance = principal;
        for (int k = 1; k <= emisLeft; k++) {
            Money cumulative = k == emisLeft ? principal
                    : Money.ofPaise(p.multiply(BigDecimal.valueOf(k)).divide(n, 0, MathPolicy.MONEY_ROUNDING)
                            .longValueExact());
            Money repaid = cumulative.minus(principal.minus(balance));
            Money interest = stated.minus(repaid);
            out.add(new AmortisingLoan.Instalment(k, balance, interest, repaid, stated, balance.minus(repaid)));
            balance = balance.minus(repaid);
        }
        Money interestRemaining = total.minus(principal);
        return new Result(LoanType.CARD_EMI, stated, false, Optional.empty(), effective, interestRemaining, total,
                emisLeft, Optional.of(payoffCycleOf(emisLeft, reference, startDay)), false, false, false, out);
    }

    private static boolean balloon(List<AmortisingLoan.Instalment> schedule, Money used) {
        if (schedule.isEmpty()) {
            return false;
        }
        // Per-instalment paise rounding can leave the last payment a few paise high; that is not a balloon.
        long tolerance = (long) schedule.size() * ROUNDING_PAISE_PER_INSTALMENT;
        return schedule.get(schedule.size() - 1).payment().paise() > used.paise() + tolerance;
    }

    /** Cycle of the {@code count}-th payment counting the reference cycle as the first; null if none. */
    private static CycleCalendar.Cycle payoffCycleOf(int count, LocalDate reference, int startDay) {
        if (count <= 0) {
            return null;
        }
        CycleCalendar.Cycle c = CycleCalendar.cycleFor(reference, startDay);
        for (int k = 1; k < count; k++) {
            c = CycleCalendar.next(c, startDay);
        }
        return c;
    }
}
