package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DatedGoalTest {

    private static final int START_DAY = 25;
    private static final LocalDate REF = LocalDate.of(2026, 10, 25);
    private static final LocalDate TARGET_DATE = LocalDate.of(2028, 12, 26);
    private static final Money TARGET = Money.ofPaise(75_000_000L);

    private static DatedGoal.Result eval(Money current, Money planned, LocalDate date) {
        return DatedGoal.evaluate(TARGET, current, planned, date, REF, START_DAY);
    }

    @Test
    void acceptanceTwentySixCyclesFromZero() {
        DatedGoal.Result r = eval(Money.ZERO, Money.ZERO, TARGET_DATE);
        assertEquals(26, r.cyclesRemaining());
        // 75,000,000 / 26 = 2,884,615.38 -> 2,884,615 paise = Rs 28,846.15
        assertEquals(2_884_615L, r.monthlyNeed().paise());
        assertEquals(DatedGoal.Status.BEHIND, r.status());
    }

    @Test
    void onTrackWhenPlannedCoversNeed() {
        assertEquals(DatedGoal.Status.ON_TRACK,
                eval(Money.ZERO, Money.ofPaise(2_884_615L), TARGET_DATE).status());
        assertEquals(DatedGoal.Status.BEHIND,
                eval(Money.ZERO, Money.ofPaise(2_884_614L), TARGET_DATE).status());
    }

    @Test
    void currentSavingsReduceNeed() {
        DatedGoal.Result r = eval(Money.ofPaise(37_500_000L), Money.ZERO, TARGET_DATE);
        assertEquals(1_442_308L, r.monthlyNeed().paise());
    }

    @Test
    void achievedWhenCurrentMeetsTarget() {
        DatedGoal.Result r = eval(TARGET, Money.ZERO, TARGET_DATE);
        assertEquals(DatedGoal.Status.ACHIEVED, r.status());
        assertEquals(0L, r.monthlyNeed().paise());
    }

    @Test
    void targetInCurrentCycleIsOneFinalDeposit() {
        DatedGoal.Result r = eval(Money.ZERO, Money.ZERO, REF.plusDays(1));
        assertEquals(0, r.cyclesRemaining());
        assertEquals(TARGET.paise(), r.monthlyNeed().paise());
    }

    @Test
    void pastDueWhenTargetCycleHasPassed() {
        DatedGoal.Result r = eval(Money.ZERO, Money.ofPaise(1L), REF.minusMonths(3));
        assertEquals(DatedGoal.Status.PAST_DUE, r.status());
        assertEquals(0L, r.monthlyNeed().paise());
        assertEquals(TARGET.paise(), r.outstanding().paise());
    }

    @Test
    void equityNeedsAtLeastMinimumHorizon() {
        RuleConfig cfg = RuleConfig.defaults();
        assertEquals(DatedGoal.InstrumentClass.SAFE_ONLY,
                DatedGoal.instrumentClass(new BigDecimal("2.9"), cfg));
        assertEquals(DatedGoal.InstrumentClass.EQUITY_PERMITTED,
                DatedGoal.instrumentClass(new BigDecimal("3.0"), cfg));
        assertEquals(DatedGoal.InstrumentClass.EQUITY_PERMITTED,
                DatedGoal.instrumentClass(new BigDecimal("3.1"), cfg));
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThrows(IllegalArgumentException.class,
                () -> DatedGoal.evaluate(Money.ofPaise(-1L), Money.ZERO, Money.ZERO, TARGET_DATE, REF, START_DAY));
    }
}
