package com.finpath.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Sinking fund for a bullet loan's principal. Pure and deterministic: no clock, no I/O; the
 * reference date is always a parameter. Cycles are counted with {@link CycleCalendar}, never
 * calendar months.
 *
 * <p>Model: the reference cycle is the first cycle in which a deposit can be made, and the due
 * date's cycle is the last, so {@code cyclesRemaining = cyclesBetween(reference, due)} deposits
 * are still to come (a due date in the current cycle is 0 cycles away and is treated as one final
 * deposit). Rounding: the per-cycle amount is outstanding paise divided by the deposit count,
 * rounded to whole paise {@link MathPolicy#MONEY_ROUNDING HALF_UP}. Because of rounding the
 * deposits may not sum exactly to the outstanding amount, so {@link Result#finalCycleAmount()}
 * is the exact remainder for the last deposit (it can differ from the regular amount by a few
 * paise).
 *
 * <p>Behind-schedule check: a straight line from the fund's start cycle to the due cycle. After
 * {@code e} of {@code n} cycles the amount that should be saved is {@code principal x e / n}
 * (HALF_UP to paise), so nothing is expected in the start cycle and the full principal in the due
 * cycle and afterwards.
 */
public final class SinkingFund {

    private SinkingFund() {
    }

    /** Where the fund stands against its schedule. */
    public enum Status {
        /** Nothing left to set aside (includes a zero principal and an over-funded pot). */
        FUNDED,
        /** Saved is at or above the straight-line amount for now. */
        ON_TRACK,
        /** Saved is below the straight-line amount for now and the due date is not yet past. */
        BEHIND,
        /**
         * Due cycle has passed and the principal is not fully saved. Implies behind schedule:
         * the catch-up is the whole outstanding amount.
         */
        PAST_DUE
    }

    /**
     * @param cyclesRemaining cycles from the reference cycle to the due cycle; negative when the
     *                        due date is past (never clamped, so callers can show how overdue)
     * @param outstanding principal not yet saved, never negative
     * @param setAsidePerCycle required deposit per remaining cycle; zero when funded or past due
     * @param finalCycleAmount exact remainder for the last deposit; zero when funded or past due
     * @param expectedSavedByNow straight-line amount that should be saved by the reference cycle
     * @param savedDelta saved minus expected; negative means behind, positive means ahead
     * @param catchUp amount needed now to get back to schedule; zero unless behind or past due
     * @param status on-track classification
     */
    public record Result(int cyclesRemaining, Money outstanding, Money setAsidePerCycle,
                         Money finalCycleAmount, Money expectedSavedByNow, Money savedDelta,
                         Money catchUp, Status status) {
    }

    /**
     * @param principal bullet principal due on {@code dueDate}
     * @param alreadySaved amount already set aside for it
     * @param fundStart date the fund began (its cycle is the start of the straight line)
     * @param dueDate date the principal is due
     * @param reference the "today" for the calculation
     * @param startDay user's cycle start day, 1..31
     * @throws IllegalArgumentException for a negative amount or invalid start day
     */
    public static Result evaluate(Money principal, Money alreadySaved, LocalDate fundStart,
                                  LocalDate dueDate, LocalDate reference, int startDay) {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(alreadySaved, "alreadySaved");
        Objects.requireNonNull(fundStart, "fundStart");
        Objects.requireNonNull(dueDate, "dueDate");
        Objects.requireNonNull(reference, "reference");
        if (principal.isNegative()) {
            throw new IllegalArgumentException("principal must not be negative");
        }
        if (alreadySaved.isNegative()) {
            throw new IllegalArgumentException("alreadySaved must not be negative");
        }
        int cycles = CycleCalendar.cyclesBetween(reference, dueDate, startDay);
        long outstandingPaise = Math.max(0L, principal.minus(alreadySaved).paise());
        Money outstanding = Money.ofPaise(outstandingPaise);

        Money expected = expectedSaved(principal, fundStart, dueDate, reference, startDay);
        Money delta = alreadySaved.minus(expected);
        Money catchUp = delta.isNegative() ? Money.ofPaise(-delta.paise()) : Money.ZERO;

        if (outstandingPaise == 0L) {
            return new Result(cycles, outstanding, Money.ZERO, Money.ZERO, expected, delta,
                    Money.ZERO, Status.FUNDED);
        }
        if (cycles < 0) {
            // Overdue: no future cycle to spread over, so no per-cycle set-aside; the whole
            // shortfall is the catch-up.
            return new Result(cycles, outstanding, Money.ZERO, Money.ZERO, expected, delta,
                    outstanding, Status.PAST_DUE);
        }
        int deposits = Math.max(cycles, 1);
        long perCycle = BigDecimal.valueOf(outstandingPaise)
                .divide(BigDecimal.valueOf(deposits), 0, MathPolicy.MONEY_ROUNDING).longValueExact();
        long last = outstandingPaise - perCycle * (deposits - 1L);
        Status status = catchUp.paise() > 0 ? Status.BEHIND : Status.ON_TRACK;
        return new Result(cycles, outstanding, Money.ofPaise(perCycle), Money.ofPaise(last),
                expected, delta, catchUp, status);
    }

    private static Money expectedSaved(Money principal, LocalDate fundStart, LocalDate dueDate,
                                       LocalDate reference, int startDay) {
        int total = CycleCalendar.cyclesBetween(fundStart, dueDate, startDay);
        int elapsed = CycleCalendar.cyclesBetween(fundStart, reference, startDay);
        if (total <= 0 || elapsed >= total) {
            return principal;
        }
        if (elapsed <= 0) {
            return Money.ZERO;
        }
        return Money.ofPaise(BigDecimal.valueOf(principal.paise())
                .multiply(BigDecimal.valueOf(elapsed))
                .divide(BigDecimal.valueOf(total), 0, MathPolicy.MONEY_ROUNDING).longValueExact());
    }
}
