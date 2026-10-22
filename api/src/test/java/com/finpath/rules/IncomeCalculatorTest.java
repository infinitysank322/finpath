package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.finpath.rules.IncomeCalculator.Frequency;
import com.finpath.rules.IncomeCalculator.Source;
import com.finpath.rules.IncomeCalculator.Type;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncomeCalculatorTest {

    private static final int START_DAY = 25;
    private static final CycleCalendar.Cycle CYCLE =
            CycleCalendar.cycleFor(LocalDate.of(2026, 10, 30), START_DAY); // 2026-10-25 .. 2026-11-25

    private static Source monthly(long paise, LocalDate start, LocalDate end) {
        return new Source(Type.SALARY, paise, Frequency.MONTHLY, start, end);
    }

    private static Source oneOff(long paise, LocalDate date) {
        return new Source(Type.BONUS, paise, Frequency.ONE_OFF, date, null);
    }

    @Test
    void emptyListIsZero() {
        assertEquals(0L, IncomeCalculator.cycleTotal(List.of(), CYCLE));
    }

    @Test
    void openEndedMonthlyCountsInFullEveryCycleAfterStart() {
        Source s = monthly(10_000_000L, LocalDate.of(2025, 1, 10), null);
        assertTrue(IncomeCalculator.countsIn(s, CYCLE));
        assertTrue(IncomeCalculator.countsIn(s, CycleCalendar.next(CYCLE, START_DAY)));
    }

    @Test
    void monthlyStartingOnLastDayOfCycleCountsInFullNoProRating() {
        Source s = monthly(10_000_000L, CYCLE.lastDay(), null);
        assertTrue(IncomeCalculator.countsIn(s, CYCLE));
        assertEquals(10_000_000L, IncomeCalculator.cycleTotal(List.of(s), CYCLE));
    }

    @Test
    void monthlyStartingOnEndExclusiveBelongsToNextCycleOnly() {
        Source s = monthly(10_000_000L, CYCLE.endExclusive(), null);
        assertFalse(IncomeCalculator.countsIn(s, CYCLE));
        assertTrue(IncomeCalculator.countsIn(s, CycleCalendar.next(CYCLE, START_DAY)));
    }

    @Test
    void endDateIsInclusiveOnCycleStart() {
        Source s = monthly(10_000_000L, LocalDate.of(2025, 1, 1), CYCLE.start());
        assertTrue(IncomeCalculator.countsIn(s, CYCLE));
    }

    @Test
    void endedTheDayBeforeCycleStartDoesNotCount() {
        Source s = monthly(10_000_000L, LocalDate.of(2025, 1, 1), CYCLE.start().minusDays(1));
        assertFalse(IncomeCalculator.countsIn(s, CYCLE));
        assertEquals(0L, IncomeCalculator.cycleTotal(List.of(s), CYCLE));
    }

    @Test
    void futureSourceDoesNotCount() {
        Source s = monthly(10_000_000L, CYCLE.endExclusive().plusMonths(2), null);
        assertFalse(IncomeCalculator.countsIn(s, CYCLE));
    }

    @Test
    void sourceStartingAndEndingInsideOneCycleCountsOnce() {
        Source s = monthly(777L, CYCLE.start().plusDays(3), CYCLE.start().plusDays(5));
        assertEquals(777L, IncomeCalculator.cycleTotal(List.of(s), CYCLE));
    }

    @Test
    void oneOffCountsOnlyInItsOwnCycle() {
        Source s = oneOff(1_400_000L, LocalDate.of(2026, 11, 3));
        CycleCalendar.Cycle prev = CycleCalendar.cycleFor(CYCLE.start().minusDays(1), START_DAY);
        CycleCalendar.Cycle next = CycleCalendar.next(CYCLE, START_DAY);
        assertTrue(IncomeCalculator.countsIn(s, CYCLE));
        assertFalse(IncomeCalculator.countsIn(s, prev));
        assertFalse(IncomeCalculator.countsIn(s, next));
    }

    @Test
    void oneOffOnCycleBoundsEdges() {
        CycleCalendar.Cycle next = CycleCalendar.next(CYCLE, START_DAY);
        Source onStart = oneOff(100L, CYCLE.start());
        Source onLastDay = oneOff(100L, CYCLE.lastDay());
        Source onEndExclusive = oneOff(100L, CYCLE.endExclusive());
        assertTrue(IncomeCalculator.countsIn(onStart, CYCLE));
        assertTrue(IncomeCalculator.countsIn(onLastDay, CYCLE));
        assertFalse(IncomeCalculator.countsIn(onEndExclusive, CYCLE));
        assertTrue(IncomeCalculator.countsIn(onEndExclusive, next));
    }

    @Test
    void startDay31ClampsInFebruary() {
        CycleCalendar.Cycle feb = CycleCalendar.cycleFor(LocalDate.of(2027, 2, 28), 31); // 2027-02-28 .. 2027-03-31
        Source onClampedStart = oneOff(500L, LocalDate.of(2027, 2, 28));
        Source onMar30 = oneOff(500L, LocalDate.of(2027, 3, 30));
        Source onMar31 = oneOff(500L, LocalDate.of(2027, 3, 31));
        assertTrue(IncomeCalculator.countsIn(onClampedStart, feb));
        assertTrue(IncomeCalculator.countsIn(onMar30, feb));
        assertFalse(IncomeCalculator.countsIn(onMar31, feb));
        Source endsFeb27 = monthly(500L, LocalDate.of(2026, 1, 1), LocalDate.of(2027, 2, 27));
        assertFalse(IncomeCalculator.countsIn(endsFeb27, feb));
    }

    @Test
    void cycleSpanningYearEnd() {
        CycleCalendar.Cycle dec = CycleCalendar.cycleFor(LocalDate.of(2027, 1, 5), START_DAY); // 2026-12-25 .. 2027-01-25
        assertEquals(LocalDate.of(2026, 12, 25), dec.start());
        assertTrue(IncomeCalculator.countsIn(oneOff(1L, LocalDate.of(2027, 1, 1)), dec));
    }

    @Test
    void paiseArithmeticIsExactAndMixesRecurringAndOneOff() {
        // Rs 1,00,000.01 + Rs 25,000.99 + Rs 14,000.00 bonus, in paise
        List<Source> sources = List.of(
                monthly(10_000_001L, LocalDate.of(2024, 4, 1), null),
                new Source(Type.RENTAL, 2_500_099L, Frequency.MONTHLY, LocalDate.of(2024, 4, 1), null),
                oneOff(1_400_000L, CYCLE.start().plusDays(10)),
                oneOff(9_999L, CYCLE.endExclusive()));
        assertEquals(10_000_001L + 2_500_099L + 1_400_000L, IncomeCalculator.cycleTotal(sources, CYCLE));
    }

    @Test
    void sumBeyondIntRangeDoesNotOverflow() {
        long big = 3_000_000_000L;
        List<Source> sources = List.of(monthly(big, LocalDate.of(2020, 1, 1), null),
                monthly(big, LocalDate.of(2020, 1, 1), null));
        assertEquals(6_000_000_000L, IncomeCalculator.cycleTotal(sources, CYCLE));
    }

    @Test
    void zeroAmountContributesNothing() {
        assertEquals(0L, IncomeCalculator.cycleTotal(List.of(monthly(0L, LocalDate.of(2020, 1, 1), null)), CYCLE));
    }

    @Test
    void invalidSourcesRejected() {
        LocalDate d = LocalDate.of(2026, 1, 1);
        assertThrows(IllegalArgumentException.class, () -> monthly(1L, d, d.minusDays(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new Source(Type.BONUS, 1L, Frequency.ONE_OFF, d, d));
        assertThrows(IllegalArgumentException.class, () -> monthly(-1L, d, null));
    }
}
