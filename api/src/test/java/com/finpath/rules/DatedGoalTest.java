package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DatedGoalTest {

    private static final RuleConfig CFG = RuleConfig.defaults();
    private static final LocalDate REF = LocalDate.of(2026, 10, 25);

    private static DatedGoal.Result run(long target, long saved, long planned, LocalDate due) {
        return DatedGoal.evaluate(Money.ofPaise(target), Money.ofPaise(saved), Money.ofPaise(planned), REF, due,
                25, CFG);
    }

    @Test
    void monthlyNeedSpreadsShortfallOverCycles() {
        DatedGoal.Result r = run(12_000_000L, 0, 0, REF.plusMonths(24));
        assertEquals(24, r.cyclesRemaining());
        assertEquals(500_000L, r.monthlyNeed().paise());
        assertEquals(DatedGoal.Status.BEHIND, r.status());
    }

    @Test
    void savedAmountReducesNeed() {
        DatedGoal.Result r = run(12_000_000L, 6_000_000L, 250_000L, REF.plusMonths(24));
        assertEquals(250_000L, r.monthlyNeed().paise());
        assertEquals(DatedGoal.Status.ON_TRACK, r.status());
    }

    @Test
    void needRoundsHalfUp() {
        assertEquals(333_334L, run(1_000_001L, 0, 0, REF.plusMonths(3)).monthlyNeed().paise());
    }

    @Test
    void achievedWhenSavedCoversTarget() {
        DatedGoal.Result r = run(1_000_000L, 1_000_000L, 0, REF.plusMonths(12));
        assertEquals(DatedGoal.Status.ACHIEVED, r.status());
        assertEquals(0L, r.monthlyNeed().paise());
    }

    @Test
    void pastDueNeverNegative() {
        DatedGoal.Result r = run(1_000_000L, 0, 0, REF.minusMonths(2));
        assertEquals(DatedGoal.Status.PAST_DUE, r.status());
        assertEquals(0L, r.monthlyNeed().paise());
        assertEquals(1_000_000L, r.shortfall().paise());
    }

    @Test
    void sameCycleTakesOneDeposit() {
        DatedGoal.Result r = run(1_000_000L, 0, 0, REF.plusDays(2));
        assertEquals(0, r.cyclesRemaining());
        assertEquals(1_000_000L, r.monthlyNeed().paise());
    }

    @Test
    void equityNeedsThreeYearHorizon() {
        assertEquals(DatedGoal.InstrumentClass.SAFE_ONLY, run(1L, 0, 0, REF.plusMonths(35)).instrumentClass());
        assertEquals(DatedGoal.InstrumentClass.EQUITY_ALLOWED, run(1L, 0, 0, REF.plusMonths(36)).instrumentClass());
        assertEquals(DatedGoal.InstrumentClass.EQUITY_ALLOWED, run(1L, 0, 0, REF.plusMonths(37)).instrumentClass());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> run(-1, 0, 0, REF.plusMonths(1)));
        assertThrows(NullPointerException.class,
                () -> DatedGoal.evaluate(null, Money.ZERO, Money.ZERO, REF, REF, 25, CFG));
    }
}
