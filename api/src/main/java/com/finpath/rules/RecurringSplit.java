package com.finpath.rules;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Splits a periodic bill (quarterly, yearly) into one instalment per budget cycle. Integer paise
 * only: the floor share goes to every instalment but the last, which takes the remainder, so the
 * instalments always add up to the bill exactly. Dates are passed in; nothing reads the clock.
 */
public final class RecurringSplit {

    private static final int MONTHS_PER_QUARTER = 3;
    private static final int MONTHS_PER_YEAR = 12;

    private RecurringSplit() {
    }

    public enum Frequency { MONTHLY, QUARTERLY, YEARLY }

    /** A recurring bill: {@code billPaise} per period; {@code endDate} null means open-ended. */
    public record Template(long billPaise, Frequency frequency, LocalDate startDate, LocalDate endDate) {
        public Template {
            Objects.requireNonNull(frequency, "frequency");
            Objects.requireNonNull(startDate, "startDate");
            if (billPaise <= 0) {
                throw new IllegalArgumentException("billPaise must be positive");
            }
            if (endDate != null && endDate.isBefore(startDate)) {
                throw new IllegalArgumentException("endDate before startDate");
            }
        }
    }

    /** The instalment due in a cycle: 0-based index within the period, period length, amount. */
    public record Due(int index, int periods, long amountPaise) {
    }

    public static int periodMonths(Frequency f) {
        return switch (Objects.requireNonNull(f, "frequency")) {
            case MONTHLY -> 1;
            case QUARTERLY -> MONTHS_PER_QUARTER;
            case YEARLY -> MONTHS_PER_YEAR;
        };
    }

    /** Instalment {@code index} (0-based) of {@code periods}; the last takes the remainder paise. */
    public static long instalment(long billPaise, int periods, int index) {
        if (billPaise <= 0) {
            throw new IllegalArgumentException("billPaise must be positive");
        }
        if (periods < 1) {
            throw new IllegalArgumentException("periods must be at least 1");
        }
        if (index < 0 || index >= periods) {
            throw new IllegalArgumentException("index out of range");
        }
        long base = billPaise / periods;
        return index == periods - 1 ? billPaise - base * (periods - 1) : base;
    }

    /** True when every instalment would be at least one paise. */
    public static boolean splittable(long billPaise, Frequency f) {
        return billPaise >= periodMonths(f);
    }

    /** The instalment the template owes in {@code cycle}, or empty when it is not active or has no turn. */
    public static Optional<Due> due(Template t, CycleCalendar.Cycle cycle, int startDay) {
        Objects.requireNonNull(t, "template");
        Objects.requireNonNull(cycle, "cycle");
        if (!t.startDate().isBefore(cycle.endExclusive())) {
            return Optional.empty();
        }
        if (t.endDate() != null && t.endDate().isBefore(cycle.start())) {
            return Optional.empty();
        }
        int m = CycleCalendar.cyclesBetween(t.startDate(), cycle.start(), startDay);
        if (m < 0) {
            return Optional.empty();
        }
        int periods = periodMonths(t.frequency());
        int index = m % periods;
        return Optional.of(new Due(index, periods, instalment(t.billPaise(), periods, index)));
    }
}
