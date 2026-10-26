package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RemindersTest {

    private static LocalDate d(int y, int m, int day) {
        return LocalDate.of(y, m, day);
    }

    private static void assertDue(int dueDay, LocalDate today, LocalDate remindOn, LocalDate due) {
        Reminders.Reminder r = Reminders.loanDue(dueDay, today);
        assertEquals(remindOn, r.remindOn(), "remindOn");
        assertEquals(due, r.dueDate(), "dueDate");
    }

    @Test
    void dueDay5MayRemindsOnApril30() {
        assertDue(5, d(2027, 4, 30), d(2027, 4, 30), d(2027, 5, 5));
    }

    @Test
    void dueDay5AprilRemindsOnMarch31() {
        assertDue(5, d(2027, 3, 31), d(2027, 3, 31), d(2027, 4, 5));
    }

    @Test
    void dueDay5MarchNonLeapRemindsOnFeb28() {
        assertDue(5, d(2027, 2, 28), d(2027, 2, 28), d(2027, 3, 5));
    }

    @Test
    void dueDay5MarchLeapRemindsOnFeb29() {
        assertDue(5, d(2028, 2, 29), d(2028, 2, 29), d(2028, 3, 5));
    }

    @Test
    void dueDay5JanuaryRollsToPreviousDecember() {
        assertDue(5, d(2027, 12, 31), d(2027, 12, 31), d(2028, 1, 5));
        assertDue(5, d(2027, 12, 20), d(2027, 12, 31), d(2028, 1, 5));
    }

    @Test
    void dueDay6RemindsOnThe1st() {
        assertDue(6, d(2027, 6, 1), d(2027, 6, 1), d(2027, 6, 6));
    }

    @Test
    void dueDay31InFebruaryClampsTo28ThenRemindsOn23() {
        assertDue(31, d(2027, 2, 10), d(2027, 2, 23), d(2027, 2, 28));
    }

    @Test
    void dueDay31InLeapFebruaryClampsTo29() {
        assertDue(31, d(2028, 2, 10), d(2028, 2, 24), d(2028, 2, 29));
    }

    @Test
    void dueDay31InAprilClampsTo30() {
        assertDue(31, d(2027, 4, 1), d(2027, 4, 25), d(2027, 4, 30));
    }

    @Test
    void dueDay30InFebruaryClamps() {
        assertDue(30, d(2027, 2, 1), d(2027, 2, 23), d(2027, 2, 28));
    }

    @Test
    void todayOnDueDateReturnsThatDate() {
        assertDue(5, d(2027, 5, 5), d(2027, 4, 30), d(2027, 5, 5));
    }

    @Test
    void dayAfterDueDateReturnsNextMonth() {
        assertDue(5, d(2027, 5, 6), d(2027, 5, 31), d(2027, 6, 5));
    }

    @Test
    void dayAfterDueDateInDecemberRollsYear() {
        assertDue(5, d(2027, 12, 6), d(2027, 12, 31), d(2028, 1, 5));
        assertDue(31, d(2027, 12, 31), d(2027, 12, 26), d(2027, 12, 31));
    }

    @Test
    void dayAfterClampedDueDateMovesToNextMonthsClampedDate() {
        assertDue(31, d(2027, 2, 28), d(2027, 2, 23), d(2027, 2, 28));
        assertDue(31, d(2027, 3, 1), d(2027, 3, 26), d(2027, 3, 31));
    }

    @Test
    void invalidDueDayRejected() {
        assertThrows(IllegalArgumentException.class, () -> Reminders.loanDue(0, d(2027, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> Reminders.loanDue(32, d(2027, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> Reminders.loanDue(-1, d(2027, 1, 1)));
    }

    @Test
    void nullTodayRejected() {
        assertThrows(NullPointerException.class, () -> Reminders.loanDue(5, null));
        assertThrows(NullPointerException.class, () -> Reminders.cycleStart(25, null));
    }

    @Test
    void cycleStartPresentOnDayZeroOneAndTwo() {
        for (int day = 25; day <= 27; day++) {
            Optional<Reminders.Reminder> r = Reminders.cycleStart(25, d(2026, 10, day));
            assertTrue(r.isPresent(), "day " + day);
            assertEquals(d(2026, 10, 25), r.get().remindOn());
            assertEquals(d(2026, 10, 25), r.get().dueDate());
        }
    }

    @Test
    void cycleStartEmptyFromDayThree() {
        assertTrue(Reminders.cycleStart(25, d(2026, 10, 28)).isEmpty());
        assertTrue(Reminders.cycleStart(25, d(2026, 11, 24)).isEmpty());
    }

    @Test
    void cycleStartDay31InApril() {
        Optional<Reminders.Reminder> r = Reminders.cycleStart(31, d(2027, 4, 30));
        assertEquals(d(2027, 4, 30), r.orElseThrow().dueDate());
        assertTrue(Reminders.cycleStart(31, d(2027, 5, 3)).isEmpty());
    }

    @Test
    void cycleStartDay31InFebruary() {
        assertEquals(d(2027, 2, 28), Reminders.cycleStart(31, d(2027, 2, 28)).orElseThrow().dueDate());
        assertEquals(d(2027, 2, 28), Reminders.cycleStart(31, d(2027, 3, 2)).orElseThrow().dueDate());
        assertTrue(Reminders.cycleStart(31, d(2027, 3, 3)).isEmpty());
        assertEquals(d(2028, 2, 29), Reminders.cycleStart(31, d(2028, 2, 29)).orElseThrow().dueDate());
    }

    @Test
    void cycleStartAcrossYearEnd() {
        assertEquals(d(2027, 12, 25), Reminders.cycleStart(25, d(2027, 12, 27)).orElseThrow().dueDate());
        assertEquals(d(2027, 12, 31), Reminders.cycleStart(31, d(2028, 1, 2)).orElseThrow().dueDate());
    }

    @Test
    void cycleStartInvalidDayRejected() {
        assertThrows(IllegalArgumentException.class, () -> Reminders.cycleStart(0, d(2027, 1, 1)));
        assertThrows(IllegalArgumentException.class, () -> Reminders.cycleStart(32, d(2027, 1, 1)));
    }

    @Test
    void earlyDueDaysReachIntoPreviousMonthAndYear() {
        Reminders.Reminder r = Reminders.loanDue(1, d(2028, 1, 1));
        assertEquals(d(2027, 12, 27), r.remindOn());
        assertEquals(d(2028, 1, 1), r.dueDate());
    }

    @Test
    void lateDueDaysClampInShortFebruary() {
        assertEquals(d(2027, 2, 28), Reminders.loanDue(29, d(2027, 2, 20)).dueDate());
        assertEquals(d(2027, 2, 28), Reminders.loanDue(30, d(2027, 2, 20)).dueDate());
        assertEquals(d(2028, 2, 29), Reminders.loanDue(29, d(2028, 2, 20)).dueDate());
        assertEquals(d(2027, 1, 26), Reminders.loanDue(31, d(2027, 1, 20)).remindOn());
    }

    @Test
    void cycleStartRejectsNonPositiveStartDay() {
        assertThrows(IllegalArgumentException.class, () -> Reminders.cycleStart(-1, d(2027, 4, 1)));
    }
}
