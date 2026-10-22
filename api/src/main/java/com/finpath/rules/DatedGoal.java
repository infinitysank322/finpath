package com.finpath.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Dated goals (FR-19). Pure: the monthly need spreads what is still missing over the cycles left
 * until the target date (same cycle counting as {@link SinkingFund}), and the horizon decides
 * which instrument class is allowed. Equity is only permitted once the horizon reaches
 * {@link RuleConfig#equityMinHorizonYears()}.
 */
public final class DatedGoal {

    private static final int CYCLES_PER_YEAR = 12;

    private DatedGoal() {
    }

    public enum Status { ACHIEVED, ON_TRACK, BEHIND, PAST_DUE }

    public enum InstrumentClass { SAFE_ONLY, EQUITY_ALLOWED }

    /**
     * {@code cyclesRemaining} counts cycles from the reference cycle to the target-date cycle;
     * {@code monthlyNeed} is the outstanding amount over that many cycles (rounded half-up to
     * paise), zero when achieved or past due; {@code shortfall} is what is still missing.
     */
    public record Result(int cyclesRemaining, Money shortfall, Money monthlyNeed, Status status,
                         InstrumentClass instrumentClass) {
    }

    public static Result evaluate(Money target, Money saved, Money plannedMonthly, LocalDate reference,
                                  LocalDate targetDate, int startDay, RuleConfig config) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(saved, "saved");
        Objects.requireNonNull(plannedMonthly, "plannedMonthly");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(targetDate, "targetDate");
        Objects.requireNonNull(config, "config");
        if (target.isNegative() || saved.isNegative() || plannedMonthly.isNegative()) {
            throw new IllegalArgumentException("amounts must not be negative");
        }
        int cycles = CycleCalendar.cyclesBetween(reference, targetDate, startDay);
        InstrumentClass instrument = instrumentFor(cycles, config);
        Money shortfall = Money.ofPaise(Math.max(0L, target.paise() - saved.paise()));
        if (shortfall.paise() == 0L) {
            return new Result(cycles, Money.ZERO, Money.ZERO, Status.ACHIEVED, instrument);
        }
        if (cycles < 0) {
            return new Result(cycles, shortfall, Money.ZERO, Status.PAST_DUE, instrument);
        }
        // The target date's own cycle still takes one deposit.
        int deposits = Math.max(cycles, 1);
        Money need = Money.ofPaise(BigDecimal.valueOf(shortfall.paise())
                .divide(BigDecimal.valueOf(deposits), 0, MathPolicy.MONEY_ROUNDING).longValueExact());
        Status status = plannedMonthly.paise() >= need.paise() ? Status.ON_TRACK : Status.BEHIND;
        return new Result(cycles, shortfall, need, status, instrument);
    }

    /**
     * Equity needs a horizon of at least the configured number of years, counted in whole cycles
     * (one per month), so the gate can differ from calendar time by under one cycle.
     */
    public static InstrumentClass instrumentFor(int cyclesRemaining, RuleConfig config) {
        Objects.requireNonNull(config, "config");
        long floorCycles = Math.multiplyExact((long) config.equityMinHorizonYears(),
                (long) CYCLES_PER_YEAR);
        return cyclesRemaining >= floorCycles ? InstrumentClass.EQUITY_ALLOWED : InstrumentClass.SAFE_ONLY;
    }
}
