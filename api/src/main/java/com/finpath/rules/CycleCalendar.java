package com.finpath.rules;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * Budget-cycle arithmetic. A cycle begins on the user's start day each month and runs up to,
 * but not including, the next start. Start days 29-31 clamp to the last day of short months.
 * Dates are always passed in; nothing here reads the system clock.
 */
public final class CycleCalendar {

    public static final int MIN_START_DAY = 1;
    public static final int MAX_START_DAY = 31;

    private CycleCalendar() {
    }

    /** A cycle: {@code start} inclusive, {@code endExclusive} is the next cycle's start. */
    public record Cycle(LocalDate start, LocalDate endExclusive) {

        public LocalDate lastDay() {
            return endExclusive.minusDays(1);
        }

        public int lengthDays() {
            return (int) ChronoUnit.DAYS.between(start, endExclusive);
        }

        public boolean contains(LocalDate date) {
            return !date.isBefore(start) && date.isBefore(endExclusive);
        }
    }

    /** The cycle containing {@code date}. */
    public static Cycle cycleFor(LocalDate date, int startDay) {
        Objects.requireNonNull(date, "date");
        requireValid(startDay);
        LocalDate thisMonthStart = startIn(date.getYear(), date.getMonthValue(), startDay);
        LocalDate start = date.isBefore(thisMonthStart)
                ? startOfMonthBefore(thisMonthStart, startDay)
                : thisMonthStart;
        return new Cycle(start, nextStart(start, startDay));
    }

    /** The cycle immediately after {@code cycle}. */
    public static Cycle next(Cycle cycle, int startDay) {
        Objects.requireNonNull(cycle, "cycle");
        requireValid(startDay);
        return new Cycle(cycle.endExclusive(), nextStart(cycle.endExclusive(), startDay));
    }

    /** Days of the cycle that have passed before {@code date}; 0 on the first day. */
    public static int daysElapsed(Cycle cycle, LocalDate date) {
        requireWithin(cycle, date);
        return (int) ChronoUnit.DAYS.between(cycle.start(), date);
    }

    /** Days remaining including {@code date} itself; elapsed + left = cycle length. */
    public static int daysLeft(Cycle cycle, LocalDate date) {
        requireWithin(cycle, date);
        return (int) ChronoUnit.DAYS.between(date, cycle.endExclusive());
    }

    /** Whole cycles from the cycle containing {@code from} to the one containing {@code to}. */
    public static int cyclesBetween(LocalDate from, LocalDate to, int startDay) {
        requireValid(startDay);
        LocalDate a = cycleFor(from, startDay).start();
        LocalDate b = cycleFor(to, startDay).start();
        int months = (b.getYear() - a.getYear()) * 12 + (b.getMonthValue() - a.getMonthValue());
        // Clamped starts always fall in distinct months, so month distance equals cycle distance.
        return months;
    }

    private static LocalDate startIn(int year, int month, int startDay) {
        LocalDate first = LocalDate.of(year, month, 1);
        return first.withDayOfMonth(Math.min(startDay, first.lengthOfMonth()));
    }

    private static LocalDate startOfMonthBefore(LocalDate monthStart, int startDay) {
        LocalDate prev = monthStart.withDayOfMonth(1).minusMonths(1);
        return startIn(prev.getYear(), prev.getMonthValue(), startDay);
    }

    private static LocalDate nextStart(LocalDate start, int startDay) {
        LocalDate next = start.withDayOfMonth(1).plusMonths(1);
        return startIn(next.getYear(), next.getMonthValue(), startDay);
    }

    private static void requireValid(int startDay) {
        if (startDay < MIN_START_DAY || startDay > MAX_START_DAY) {
            throw new IllegalArgumentException("startDay must be 1..31: " + startDay);
        }
    }

    private static void requireWithin(Cycle cycle, LocalDate date) {
        if (!cycle.contains(date)) {
            throw new IllegalArgumentException("date " + date + " is outside cycle " + cycle);
        }
    }
}
