package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.finpath.rules.AmortisingLoan.Instalment;
import com.finpath.rules.AmortisingLoan.Schedule;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AmortisingLoanTest {

    private static final int START_DAY = 25;
    private static final BigDecimal TWELVE_PCT = new BigDecimal("0.12");
    private static final Money P = Money.ofPaise(9_876_500L);

    @Test
    void emiMatchesHighPrecisionReference() {
        // Reference computed offline at 40 digits: 877515.06 paise before rounding.
        assertEquals(877_515L, AmortisingLoan.emi(P, TWELVE_PCT, 12).paise());
    }

    @Test
    void zeroRateIsPrincipalOverTenureAndResidueLandsInFinalInstalment() {
        Money p = Money.ofPaise(100_100L);
        assertEquals(33_367L, AmortisingLoan.emi(p, BigDecimal.ZERO, 3).paise());
        Schedule s = AmortisingLoan.schedule(p, BigDecimal.ZERO, 3);
        assertEquals(3, s.size());
        assertEquals(33_367L, s.instalments().get(0).payment().paise());
        assertEquals(33_366L, s.instalments().get(2).payment().paise());
        assertEquals(0L, s.totalInterest().paise());
    }

    @Test
    void zeroPrincipalGivesZeroEmiAndEmptySchedule() {
        assertEquals(0L, AmortisingLoan.emi(Money.ZERO, TWELVE_PCT, 12).paise());
        Schedule s = AmortisingLoan.schedule(Money.ZERO, TWELVE_PCT, 12);
        assertEquals(0, s.size());
        assertEquals(0L, s.totalInterest().paise());
        assertEquals(0L, s.balanceAfter(5).paise());
        assertTrue(AmortisingLoan.payoffCycle(s, LocalDate.of(2030, 1, 25), START_DAY).isEmpty());
    }

    @Test
    void scheduleSumsAndChainsExactly() {
        Schedule s = AmortisingLoan.schedule(P, TWELVE_PCT, 12);
        assertEquals(12, s.size());
        assertEquals(P.paise(), s.totalPrincipal().paise());
        assertEquals(s.totalPrincipal().plus(s.totalInterest()).paise(), s.totalPaid().paise());
        Money prev = P;
        for (Instalment i : s.instalments()) {
            assertEquals(prev.paise(), i.opening().paise());
            assertEquals(i.principal().plus(i.interest()).paise(), i.payment().paise());
            assertEquals(i.opening().minus(i.principal()).paise(), i.closing().paise());
            prev = i.closing();
        }
        assertEquals(0L, prev.paise());
    }

    @Test
    void firstInstalmentInterestIsOpeningTimesMonthlyRate() {
        Instalment first = AmortisingLoan.schedule(P, TWELVE_PCT, 12).instalments().get(0);
        assertEquals(98_765L, first.interest().paise());
        assertEquals(877_515L - 98_765L, first.principal().paise());
    }

    @Test
    void finalInstalmentResidueIsSmallAndNeverNegative() {
        Schedule s = AmortisingLoan.schedule(P, TWELVE_PCT, 12);
        Instalment last = s.instalments().get(11);
        long diff = Math.abs(last.payment().paise() - AmortisingLoan.emi(P, TWELVE_PCT, 12).paise());
        assertTrue(diff <= 12, "residue should be a few paise, was " + diff);
        assertFalse(last.principal().isNegative());
        assertEquals(0L, last.closing().paise());
    }

    @Test
    void singleInstalmentTenureRepaysEverythingWithOneMonthInterest() {
        Schedule s = AmortisingLoan.schedule(P, TWELVE_PCT, 1);
        assertEquals(1, s.size());
        assertEquals(P.paise(), s.instalments().get(0).principal().paise());
        assertEquals(98_765L, s.instalments().get(0).interest().paise());
    }

    @Test
    void explicitEmiThatClearsEarlyEndsEarly() {
        Schedule s = AmortisingLoan.schedule(P, TWELVE_PCT, Money.ofPaise(2_000_000L), 120);
        assertTrue(s.size() < 12);
        assertEquals(0L, s.balanceAfter(s.size()).paise());
        assertEquals(P.paise(), s.totalPrincipal().paise());
    }

    @Test
    void explicitEmiBelowInterestIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> AmortisingLoan.schedule(P, TWELVE_PCT, Money.ofPaise(98_765L), 12));
    }

    @Test
    void invalidInputsRejected() {
        assertThrows(IllegalArgumentException.class, () -> AmortisingLoan.emi(P, TWELVE_PCT, 0));
        assertThrows(IllegalArgumentException.class, () -> AmortisingLoan.emi(Money.ofPaise(-1L), TWELVE_PCT, 12));
        assertThrows(IllegalArgumentException.class, () -> AmortisingLoan.emi(P, new BigDecimal("-0.01"), 12));
    }

    @Test
    void interestRemainingFallsAsInstalmentsArePaid() {
        Schedule s = AmortisingLoan.schedule(P, TWELVE_PCT, 12);
        assertEquals(s.totalInterest().paise(), s.interestRemainingAfter(0).paise());
        assertEquals(s.totalInterest().minus(s.instalments().get(0).interest()).paise(),
                s.interestRemainingAfter(1).paise());
        assertEquals(0L, s.interestRemainingAfter(12).paise());
        assertEquals(0L, s.interestRemainingAfter(99).paise());
        assertEquals(P.paise(), s.balanceAfter(0).paise());
    }

    @Test
    void referenceDateDrivesPaidCountOnCycleBoundaries() {
        Schedule s = AmortisingLoan.schedule(P, TWELVE_PCT, 12);
        LocalDate firstDue = LocalDate.of(2030, 1, 25);
        // Reference in the first instalment's own cycle: nothing paid yet.
        assertEquals(0, AmortisingLoan.instalmentsPaid(s, firstDue, LocalDate.of(2030, 2, 24), START_DAY));
        // Reference on the next cycle start: one paid.
        assertEquals(1, AmortisingLoan.instalmentsPaid(s, firstDue, LocalDate.of(2030, 2, 25), START_DAY));
        // Before the first cycle: nothing paid, full balance.
        assertEquals(0, AmortisingLoan.instalmentsPaid(s, firstDue, LocalDate.of(2029, 6, 1), START_DAY));
        assertEquals(P.paise(),
                AmortisingLoan.outstandingPrincipal(s, firstDue, LocalDate.of(2029, 6, 1), START_DAY).paise());
        assertEquals(s.interestRemainingAfter(1).paise(),
                AmortisingLoan.interestRemaining(s, firstDue, LocalDate.of(2030, 2, 25), START_DAY).paise());
    }

    @Test
    void cycleSpanningMonthEndWithClampedStartDay() {
        Schedule s = AmortisingLoan.schedule(P, TWELVE_PCT, 3);
        LocalDate firstDue = LocalDate.of(2031, 1, 31);
        // Start day 31 clamps to 28 Feb 2031; Feb cycle starts 28 Feb.
        assertEquals(1, AmortisingLoan.instalmentsPaid(s, firstDue, LocalDate.of(2031, 2, 28), 31));
        assertEquals(0, AmortisingLoan.instalmentsPaid(s, firstDue, LocalDate.of(2031, 2, 27), 31));
    }

    @Test
    void payoffCycleIsCycleOfLastInstalment() {
        Schedule s = AmortisingLoan.schedule(P, TWELVE_PCT, 12);
        CycleCalendar.Cycle c = AmortisingLoan.payoffCycle(s, LocalDate.of(2030, 1, 25), START_DAY).orElseThrow();
        assertEquals(LocalDate.of(2030, 12, 25), c.start());
        assertEquals(LocalDate.of(2031, 1, 25), c.endExclusive());
    }

    @Test
    void closedLoanHasNothingLeftAfterPayoffCycle() {
        Schedule s = AmortisingLoan.schedule(P, TWELVE_PCT, 12);
        LocalDate firstDue = LocalDate.of(2030, 1, 25);
        assertFalse(AmortisingLoan.isClosed(s, firstDue, LocalDate.of(2030, 12, 31), START_DAY));
        assertTrue(AmortisingLoan.isClosed(s, firstDue, LocalDate.of(2031, 1, 25), START_DAY));
        assertEquals(0L, AmortisingLoan.interestRemaining(s, firstDue, LocalDate.of(2040, 1, 1), START_DAY).paise());
        assertEquals(0L, AmortisingLoan.outstandingPrincipal(s, firstDue, LocalDate.of(2040, 1, 1), START_DAY).paise());
    }
}
