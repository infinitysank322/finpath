package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class CycleCalendarTest {

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    @Test
    void defaultStartDay25() {
        var c = CycleCalendar.cycleFor(d(2026, 10, 21), 25);
        assertEquals(d(2026, 9, 25), c.start());
        assertEquals(d(2026, 10, 25), c.endExclusive());
        assertEquals(d(2026, 10, 24), c.lastDay());
        assertEquals(30, c.lengthDays());
    }

    @Test
    void startDayItselfBelongsToNewCycle() {
        assertEquals(d(2026, 10, 25), CycleCalendar.cycleFor(d(2026, 10, 25), 25).start());
        assertEquals(d(2026, 9, 25), CycleCalendar.cycleFor(d(2026, 10, 24), 25).start());
    }

    @Test
    void startDay31InFebruaryClamps() {
        var c = CycleCalendar.cycleFor(d(2027, 2, 28), 31);
        assertEquals(d(2027, 2, 28), c.start());
        assertEquals(d(2027, 3, 31), c.endExclusive());
        var leap = CycleCalendar.cycleFor(d(2028, 2, 29), 31);
        assertEquals(d(2028, 2, 29), leap.start());
        assertEquals(d(2028, 2, 29), CycleCalendar.cycleFor(d(2028, 3, 5), 31).start());
        assertEquals(d(2028, 1, 31), CycleCalendar.cycleFor(d(2028, 2, 28), 31).start());
    }

    @Test
    void startDay29And30Clamp() {
        assertEquals(d(2027, 2, 28), CycleCalendar.cycleFor(d(2027, 2, 28), 29).start());
        assertEquals(d(2027, 2, 28), CycleCalendar.cycleFor(d(2027, 3, 1), 30).start());
        assertEquals(d(2027, 3, 30), CycleCalendar.cycleFor(d(2027, 4, 2), 30).start());
    }

    @Test
    void cycleSpanningMonthEndReportsDayCount() {
        var c = CycleCalendar.cycleFor(d(2027, 1, 30), 25);
        assertEquals(d(2027, 1, 25), c.start());
        assertEquals(d(2027, 2, 25), c.endExclusive());
        assertEquals(31, c.lengthDays());
        var feb = CycleCalendar.cycleFor(d(2027, 2, 25), 25);
        assertEquals(28, feb.lengthDays());
    }

    @Test
    void yearBoundary() {
        var c = CycleCalendar.cycleFor(d(2027, 1, 3), 25);
        assertEquals(d(2026, 12, 25), c.start());
        assertEquals(d(2027, 1, 25), c.endExclusive());
    }

    @Test
    void startDay1IsCalendarMonth() {
        var c = CycleCalendar.cycleFor(d(2026, 10, 21), 1);
        assertEquals(d(2026, 10, 1), c.start());
        assertEquals(d(2026, 11, 1), c.endExclusive());
    }

    @Test
    void elapsedAndLeftSumToLength() {
        var c = CycleCalendar.cycleFor(d(2026, 10, 21), 25);
        assertEquals(26, CycleCalendar.daysElapsed(c, d(2026, 10, 21)));
        assertEquals(4, CycleCalendar.daysLeft(c, d(2026, 10, 21)));
        assertEquals(0, CycleCalendar.daysElapsed(c, c.start()));
        assertEquals(1, CycleCalendar.daysLeft(c, c.lastDay()));
        assertEquals(c.lengthDays(), CycleCalendar.daysElapsed(c, d(2026, 10, 3))
                + CycleCalendar.daysLeft(c, d(2026, 10, 3)));
    }

    @Test
    void outsideCycleRejected() {
        var c = CycleCalendar.cycleFor(d(2026, 10, 21), 25);
        assertThrows(IllegalArgumentException.class, () -> CycleCalendar.daysLeft(c, c.endExclusive()));
        assertThrows(IllegalArgumentException.class,
                () -> CycleCalendar.daysElapsed(c, c.start().minusDays(1)));
    }

    @Test
    void invalidStartDayRejected() {
        assertThrows(IllegalArgumentException.class, () -> CycleCalendar.cycleFor(d(2026, 1, 1), 0));
        assertThrows(IllegalArgumentException.class, () -> CycleCalendar.cycleFor(d(2026, 1, 1), 32));
    }

    @Test
    void nextChainsContiguously() {
        var c = CycleCalendar.cycleFor(d(2027, 1, 31), 31);
        var n = CycleCalendar.next(c, 31);
        assertEquals(c.endExclusive(), n.start());
        assertEquals(d(2027, 2, 28), n.start());
        assertEquals(d(2027, 3, 31), n.endExclusive());
    }

    @Test
    void cyclesBetween() {
        assertEquals(0, CycleCalendar.cyclesBetween(d(2026, 10, 1), d(2026, 10, 24), 25));
        assertEquals(1, CycleCalendar.cyclesBetween(d(2026, 10, 24), d(2026, 10, 25), 25));
        assertEquals(17, CycleCalendar.cyclesBetween(d(2026, 10, 25), d(2028, 3, 25), 25));
        assertEquals(-1, CycleCalendar.cyclesBetween(d(2026, 10, 25), d(2026, 10, 24), 25));
        assertEquals(1, CycleCalendar.cyclesBetween(d(2027, 1, 31), d(2027, 2, 28), 31));
    }

    @Test
    void rulesSourceNeverReadsTheClock() throws IOException {
        Path root = Path.of("src/main/java/com/finpath/rules");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                String src = Files.readString(p);
                assertFalse(src.contains("LocalDate." + "now"), p + " reads the clock");
                assertFalse(src.contains("Clock." + "system"), p + " reads the clock");
            }
        }
    }
}
