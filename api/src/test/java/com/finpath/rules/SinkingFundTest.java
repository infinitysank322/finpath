package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class SinkingFundTest {

    private static final int START_DAY = 25;
    private static final Money PRINCIPAL = Money.ofPaise(9_000_000L);
    private static final LocalDate FUND_START = LocalDate.of(2026, 10, 25);
    private static final LocalDate DUE = LocalDate.of(2028, 3, 26);

    private static SinkingFund.Result eval(Money saved, LocalDate reference) {
        return SinkingFund.evaluate(PRINCIPAL, saved, FUND_START, DUE, reference, START_DAY);
    }

    @Test
    void acceptanceSeventeenCyclesGivesRoundedPerCycle() {
        SinkingFund.Result r = eval(Money.ZERO, FUND_START);
        assertEquals(17, r.cyclesRemaining());
        // 9,000,000 / 17 = 529,411.76 paise -> HALF_UP 529,412 (5,294.12 rupees)
        assertEquals(529_412L, r.setAsidePerCycle().paise());
        // 529,412 x 16 = 16,941,184; remainder for the last deposit is 1,058,816
        assertEquals(529_408L, r.finalCycleAmount().paise());
        assertEquals(PRINCIPAL.paise(), r.setAsidePerCycle().paise() * 16 + r.finalCycleAmount().paise());
        assertEquals(SinkingFund.Status.ON_TRACK, r.status());
        assertEquals(0L, r.catchUp().paise());
    }

    @Test
    void existingSavingsReduceNeedProportionally() {
        SinkingFund.Result r = eval(Money.ofPaise(2_500_000L), FUND_START);
        // 6,500,000 / 17 = 382,352.94 -> 382,353
        assertEquals(6_500_000L, r.outstanding().paise());
        assertEquals(382_353L, r.setAsidePerCycle().paise());
    }

    @Test
    void dueThisCycleNeedsTheWholeOutstandingNow() {
        SinkingFund.Result r = eval(Money.ofPaise(2_500_000L), LocalDate.of(2028, 3, 26));
        assertEquals(0, r.cyclesRemaining());
        assertEquals(6_500_000L, r.setAsidePerCycle().paise());
        assertEquals(6_500_000L, r.finalCycleAmount().paise());
        assertEquals(SinkingFund.Status.BEHIND, r.status());
    }

    @Test
    void lastDayOfCycleBeforeDueIsOneCycleAway() {
        SinkingFund.Result r = eval(Money.ZERO, LocalDate.of(2028, 3, 24));
        assertEquals(1, r.cyclesRemaining());
    }

    @Test
    void pastDueIsBehindWithNoNegativeSetAside() {
        SinkingFund.Result r = eval(Money.ofPaise(2_500_000L), LocalDate.of(2028, 5, 1));
        assertTrue(r.cyclesRemaining() < 0);
        assertEquals(SinkingFund.Status.PAST_DUE, r.status());
        assertEquals(0L, r.setAsidePerCycle().paise());
        assertEquals(0L, r.finalCycleAmount().paise());
        assertEquals(6_500_000L, r.catchUp().paise());
        assertEquals(PRINCIPAL.paise(), r.expectedSavedByNow().paise());
    }

    @Test
    void behindScheduleReportsDeltaAndCatchUp() {
        // 12 of 17 cycles elapsed: expected 9,000,000 x 12 / 17 = 6,352,941.18 -> 6,352,941
        SinkingFund.Result r = eval(Money.ofPaise(2_500_000L), LocalDate.of(2027, 10, 25));
        assertEquals(5, r.cyclesRemaining());
        assertEquals(6_352_941L, r.expectedSavedByNow().paise());
        assertEquals(-3_852_941L, r.savedDelta().paise());
        assertEquals(3_852_941L, r.catchUp().paise());
        assertEquals(SinkingFund.Status.BEHIND, r.status());
        assertEquals(1_300_000L, r.setAsidePerCycle().paise());
    }

    @Test
    void aheadOfScheduleIsOnTrackWithPositiveDelta() {
        SinkingFund.Result r = eval(Money.ofPaise(6_352_941L), LocalDate.of(2027, 10, 25));
        assertEquals(0L, r.savedDelta().paise());
        assertEquals(SinkingFund.Status.ON_TRACK, r.status());
        SinkingFund.Result ahead = eval(Money.ofPaise(7_500_000L), LocalDate.of(2027, 10, 25));
        assertEquals(1_147_059L, ahead.savedDelta().paise());
        assertEquals(0L, ahead.catchUp().paise());
        assertEquals(SinkingFund.Status.ON_TRACK, ahead.status());
    }

    @Test
    void fundStartCycleExpectsNothingYet() {
        SinkingFund.Result r = eval(Money.ZERO, FUND_START);
        assertEquals(0L, r.expectedSavedByNow().paise());
        assertEquals(0L, r.savedDelta().paise());
    }

    @Test
    void fullyFundedNeedsNothing() {
        SinkingFund.Result r = eval(PRINCIPAL, LocalDate.of(2027, 1, 1));
        assertEquals(SinkingFund.Status.FUNDED, r.status());
        assertEquals(0L, r.setAsidePerCycle().paise());
        assertEquals(0L, r.outstanding().paise());
        assertEquals(0L, r.catchUp().paise());
    }

    @Test
    void overFundedClampsToZeroNeverNegative() {
        SinkingFund.Result r = eval(Money.ofPaise(9_500_000L), LocalDate.of(2027, 1, 1));
        assertEquals(SinkingFund.Status.FUNDED, r.status());
        assertEquals(0L, r.setAsidePerCycle().paise());
        assertEquals(0L, r.outstanding().paise());
        assertTrue(r.savedDelta().paise() > 0);
    }

    @Test
    void fundedAfterDueDateIsFundedNotPastDue() {
        SinkingFund.Result r = eval(PRINCIPAL, LocalDate.of(2029, 1, 1));
        assertEquals(SinkingFund.Status.FUNDED, r.status());
        assertEquals(0L, r.catchUp().paise());
    }

    @Test
    void zeroPrincipalIsZeroEverywhere() {
        SinkingFund.Result r = SinkingFund.evaluate(Money.ZERO, Money.ZERO, FUND_START, DUE,
                FUND_START, START_DAY);
        assertEquals(SinkingFund.Status.FUNDED, r.status());
        assertEquals(0L, r.setAsidePerCycle().paise());
        assertEquals(0L, r.expectedSavedByNow().paise());
        assertEquals(0L, r.catchUp().paise());
    }

    @Test
    void startDay31ClampsToShortMonths() {
        // Cycles start 31 Oct, 30 Nov, 31 Dec, 31 Jan, 28 Feb: 31 Oct -> a date in the 28 Feb cycle is 4 away.
        SinkingFund.Result r = SinkingFund.evaluate(Money.ofPaise(4_000_000L), Money.ZERO,
                LocalDate.of(2026, 10, 31), LocalDate.of(2027, 2, 28), LocalDate.of(2026, 10, 31), 31);
        assertEquals(4, r.cyclesRemaining());
        assertEquals(1_000_000L, r.setAsidePerCycle().paise());
        // 30 Oct is still in the cycle that began 30 Sep, so one cycle further out
        SinkingFund.Result earlier = SinkingFund.evaluate(Money.ofPaise(4_000_000L), Money.ZERO,
                LocalDate.of(2026, 10, 30), LocalDate.of(2027, 2, 28), LocalDate.of(2026, 10, 30), 31);
        assertEquals(5, earlier.cyclesRemaining());
    }

    @Test
    void cycleSpanningYearEndIsCountedByCycles() {
        SinkingFund.Result r = SinkingFund.evaluate(Money.ofPaise(1_200_000L), Money.ZERO,
                LocalDate.of(2026, 12, 25), LocalDate.of(2027, 2, 26), LocalDate.of(2026, 12, 25), START_DAY);
        assertEquals(2, r.cyclesRemaining());
        assertEquals(600_000L, r.setAsidePerCycle().paise());
    }

    @Test
    void fundStartInDueCycleExpectsFullPrincipalAtOnce() {
        SinkingFund.Result r = SinkingFund.evaluate(PRINCIPAL, Money.ZERO, DUE, DUE, DUE, START_DAY);
        assertEquals(0, r.cyclesRemaining());
        assertEquals(PRINCIPAL.paise(), r.expectedSavedByNow().paise());
        assertEquals(PRINCIPAL.paise(), r.catchUp().paise());
        assertEquals(PRINCIPAL.paise(), r.setAsidePerCycle().paise());
        assertEquals(SinkingFund.Status.BEHIND, r.status());
    }

    @Test
    void fundStartAfterDueDateIsTreatedAsFullyDueNow() {
        // Documented behaviour: a degenerate (non-positive) schedule expects the whole principal.
        LocalDate lateStart = LocalDate.of(2028, 6, 25);
        SinkingFund.Result r = SinkingFund.evaluate(PRINCIPAL, Money.ZERO, lateStart, DUE, FUND_START, START_DAY);
        assertEquals(PRINCIPAL.paise(), r.expectedSavedByNow().paise());
        assertEquals(PRINCIPAL.paise(), r.catchUp().paise());
        assertEquals(SinkingFund.Status.BEHIND, r.status());
    }

    @Test
    void referenceBeforeFundStartExpectsNothing() {
        SinkingFund.Result r = eval(Money.ZERO, LocalDate.of(2026, 9, 25));
        assertEquals(18, r.cyclesRemaining());
        assertEquals(0L, r.expectedSavedByNow().paise());
        assertEquals(SinkingFund.Status.ON_TRACK, r.status());
    }

    @Test
    void dueDateExactlyOnCycleStartIsInThatCycle() {
        LocalDate onStart = LocalDate.of(2028, 3, 25);
        SinkingFund.Result r = SinkingFund.evaluate(PRINCIPAL, Money.ZERO, FUND_START, onStart, FUND_START, START_DAY);
        assertEquals(17, r.cyclesRemaining());
        assertEquals(529_412L, r.setAsidePerCycle().paise());
    }

    @Test
    void negativeInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SinkingFund.evaluate(Money.ofPaise(-1L),
                Money.ZERO, FUND_START, DUE, FUND_START, START_DAY));
        assertThrows(IllegalArgumentException.class, () -> SinkingFund.evaluate(PRINCIPAL,
                Money.ofPaise(-1L), FUND_START, DUE, FUND_START, START_DAY));
    }

    @Test
    void invalidStartDayAndNullsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> SinkingFund.evaluate(PRINCIPAL, Money.ZERO,
                FUND_START, DUE, FUND_START, 32));
        assertThrows(NullPointerException.class, () -> SinkingFund.evaluate(PRINCIPAL, Money.ZERO,
                FUND_START, DUE, null, START_DAY));
    }
}
