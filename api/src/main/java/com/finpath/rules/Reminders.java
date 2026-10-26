package com.finpath.rules;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Reminder dates (FR-27, T72). Pure date arithmetic on top of {@link CycleCalendar}; no money,
 * no clock. A loan without a due day produces no reminder, so callers skip it before asking.
 */
public final class Reminders {

    /** BRD default: alert 5 days before the due date. */
    public static final int LEAD_DAYS = 5;
    /** A cycle-start reminder is still delivered up to this many days late. */
    public static final int CYCLE_CATCHUP_DAYS = 2;

    private Reminders() {
    }

    /** When to remind and the date the reminder refers to (loan due date, or cycle start). */
    public record Reminder(LocalDate remindOn, LocalDate dueDate) {
    }

    /**
     * Next due date on or after {@code today} for a monthly {@code dueDay} (clamped to month
     * length) and the reminder date {@link #LEAD_DAYS} before it.
     */
    public static Reminder loanDue(int dueDay, LocalDate today) {
        Objects.requireNonNull(today, "today");
        CycleCalendar.Cycle c = CycleCalendar.cycleFor(today, dueDay);
        LocalDate due = c.start().equals(today) ? c.start() : c.endExclusive();
        return new Reminder(due.minusDays(LEAD_DAYS), due);
    }

    /** The cycle-day reminder, present only on the start day or within the catch-up window. */
    public static Optional<Reminder> cycleStart(int startDay, LocalDate today) {
        Objects.requireNonNull(today, "today");
        CycleCalendar.Cycle c = CycleCalendar.cycleFor(today, startDay);
        if (CycleCalendar.daysElapsed(c, today) > CYCLE_CATCHUP_DAYS) {
            return Optional.empty();
        }
        return Optional.of(new Reminder(c.start(), c.start()));
    }
}
