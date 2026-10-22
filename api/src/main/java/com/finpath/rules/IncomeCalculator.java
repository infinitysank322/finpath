package com.finpath.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Per-cycle income. Pure: sources and the cycle are passed in; no clock, no I/O. Amounts are
 * whole paise; nothing is divided or pro-rated, so no rounding arises.
 */
public final class IncomeCalculator {

    private IncomeCalculator() {
    }

    public enum Type { SALARY, RENTAL, BONUS, SIDE, OTHER }

    public enum Frequency { MONTHLY, ONE_OFF }

    /**
     * One income row. {@code endDate} is inclusive and optional (open-ended); it must be null for
     * ONE_OFF, where {@code startDate} is the date received.
     */
    public record Source(Type type, long amountPaise, Frequency frequency, LocalDate startDate,
            LocalDate endDate) {

        public Source {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(frequency, "frequency");
            Objects.requireNonNull(startDate, "startDate");
            if (amountPaise < 0) {
                throw new IllegalArgumentException("amountPaise must not be negative");
            }
            if (endDate != null && endDate.isBefore(startDate)) {
                throw new IllegalArgumentException("endDate before startDate");
            }
            if (frequency == Frequency.ONE_OFF && endDate != null) {
                throw new IllegalArgumentException("ONE_OFF must not have an endDate");
            }
        }
    }

    /** The single inclusion rule: does this source contribute to {@code cycle}? */
    public static boolean countsIn(Source source, CycleCalendar.Cycle cycle) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(cycle, "cycle");
        if (source.frequency() == Frequency.ONE_OFF) {
            return cycle.contains(source.startDate());
        }
        boolean startedBeforeCycleEnds = source.startDate().isBefore(cycle.endExclusive());
        boolean notEndedBeforeCycle = source.endDate() == null || !source.endDate().isBefore(cycle.start());
        return startedBeforeCycleEnds && notEndedBeforeCycle;
    }

    /** Total paise of all sources counting in {@code cycle}; 0 for an empty list. */
    public static long cycleTotal(List<Source> sources, CycleCalendar.Cycle cycle) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(cycle, "cycle");
        BigDecimal total = BigDecimal.ZERO;
        for (Source s : sources) {
            if (countsIn(s, cycle)) {
                total = total.add(BigDecimal.valueOf(s.amountPaise()));
            }
        }
        return total.longValueExact();
    }
}
