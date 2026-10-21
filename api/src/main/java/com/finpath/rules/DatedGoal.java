package com.finpath.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Dated goal maths (FR-19). Pure and deterministic; the reference date is a parameter. Cycles are
 * counted with {@link CycleCalendar}. As in {@link SinkingFund}, the reference cycle is the first
 * deposit and the target date's cycle the last, so a target in the current cycle is one final
 * deposit. The monthly need is the outstanding amount over the deposit count, rounded to paise
 * {@link MathPolicy#MONEY_ROUNDING HALF_UP}.
 */
public final class DatedGoal {

    private DatedGoal() {
    }

    /** Where the goal stands against what the user plans to set aside. */
    public enum Status {
        /** Current amount already meets the target. */
        ACHIEVED,
        /** Planned contribution covers the monthly need. */
        ON_TRACK,
        /** Planned contribution is below the monthly need. */
        BEHIND,
        /** Target date's cycle has passed and the target is not met. */
        PAST_DUE
    }

    /** Instrument class the horizon permits. */
    public enum InstrumentClass {
        /** Horizon shorter than the equity minimum: debt and cash-like instruments only. */
        SAFE_ONLY,
        /** Horizon long enough for equity. */
        EQUITY_PERMITTED
    }

    /**
     * @param cyclesRemaining cycles from the reference cycle to the target cycle; negative if past
     * @param outstanding target not yet saved, never negative
     * @param monthlyNeed required deposit per remaining cycle; zero when achieved or past due
     * @param status on-track classification
     */
    public record Result(int cyclesRemaining, Money outstanding, Money monthlyNeed, Status status) {
    }

    /**
     * @param target amount wanted by {@code targetDate}
     * @param current amount already saved towards it
     * @param plannedMonthly what the user currently sets aside per cycle
     * @throws IllegalArgumentException for a negative amount or invalid start day
     */
    public static Result evaluate(Money target, Money current, Money plannedMonthly,
                                  LocalDate targetDate, LocalDate reference, int startDay) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(plannedMonthly, "plannedMonthly");
        Objects.requireNonNull(targetDate, "targetDate");
        Objects.requireNonNull(reference, "reference");
        if (target.isNegative() || current.isNegative() || plannedMonthly.isNegative()) {
            throw new IllegalArgumentException("amounts must not be negative");
        }
        int cycles = CycleCalendar.cyclesBetween(reference, targetDate, startDay);
        Money outstanding = Money.ofPaise(Math.max(0L, target.minus(current).paise()));
        if (outstanding.paise() == 0L) {
            return new Result(cycles, outstanding, Money.ZERO, Status.ACHIEVED);
        }
        if (cycles < 0) {
            return new Result(cycles, outstanding, Money.ZERO, Status.PAST_DUE);
        }
        int deposits = Math.max(cycles, 1);
        Money need = Money.ofPaise(BigDecimal.valueOf(outstanding.paise())
                .divide(BigDecimal.valueOf(deposits), 0, MathPolicy.MONEY_ROUNDING).longValueExact());
        Status status = plannedMonthly.paise() >= need.paise() ? Status.ON_TRACK : Status.BEHIND;
        return new Result(cycles, outstanding, need, status);
    }

    /**
     * Instrument class for a horizon in years: equity is permitted from the configured minimum
     * horizon upwards, never below it.
     */
    public static InstrumentClass instrumentClass(BigDecimal horizonYears, RuleConfig config) {
        Objects.requireNonNull(horizonYears, "horizonYears");
        Objects.requireNonNull(config, "config");
        return horizonYears.compareTo(BigDecimal.valueOf(config.equityMinHorizonYears())) < 0
                ? InstrumentClass.SAFE_ONLY
                : InstrumentClass.EQUITY_PERMITTED;
    }
}
