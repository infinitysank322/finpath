package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PrepaymentSimulatorTest {

    private static final Money P = Money.ofPaise(100_000_000L);
    private static final BigDecimal RATE = new BigDecimal("0.09");
    private static final int TENURE = 120;
    private static final int START_DAY = 25;

    private static Money emi() {
        return AmortisingLoan.emi(P, RATE, TENURE);
    }

    @Test
    void emiToCloseDelegatesToAmortisingEmi() {
        assertEquals(AmortisingLoan.emi(P, RATE, 60), PrepaymentSimulator.emiToClose(P, RATE, 60));
        assertEquals(Money.ZERO, PrepaymentSimulator.emiToClose(Money.ZERO, RATE, 60));
    }

    @Test
    void closeInSavesInterestAndRaisesEmi() {
        PrepaymentSimulator.Outcome o = PrepaymentSimulator.closeIn(P, RATE, TENURE, 60);
        assertEquals(TENURE, o.baselineInstalments());
        assertEquals(60, o.newInstalments());
        assertEquals(60, o.instalmentsSaved());
        assertTrue(o.newEmi().paise() > emi().paise());
        assertEquals(AmortisingLoan.schedule(P, RATE, TENURE).totalInterest()
                .minus(AmortisingLoan.schedule(P, RATE, 60).totalInterest()), o.interestSaved());
    }

    @Test
    void closeInSameTenureSavesNothing() {
        PrepaymentSimulator.Outcome o = PrepaymentSimulator.closeIn(P, RATE, TENURE, TENURE);
        assertEquals(Money.ZERO, o.interestSaved());
        assertEquals(0, o.instalmentsSaved());
    }

    @Test
    void closeInRejectsBadTargets() {
        assertThrows(IllegalArgumentException.class, () -> PrepaymentSimulator.closeIn(P, RATE, TENURE, 0));
        assertThrows(IllegalArgumentException.class,
                () -> PrepaymentSimulator.closeIn(P, RATE, TENURE, TENURE + 1));
    }

    @Test
    void zeroPrincipalEverythingIsZero() {
        PrepaymentSimulator.Outcome c = PrepaymentSimulator.closeIn(Money.ZERO, RATE, TENURE, 60);
        assertEquals(0, c.newInstalments());
        assertEquals(Money.ZERO, c.interestSaved());
        PrepaymentSimulator.Outcome l = PrepaymentSimulator.lumpSum(Money.ZERO, RATE, Money.ZERO, TENURE, 0,
                Money.ofPaise(1_000L));
        assertEquals(0, l.newInstalments());
        assertEquals(Money.ZERO, l.interestSaved());
        assertTrue(c.newPayoffCycle(LocalDate.of(2026, 1, 25), START_DAY).isEmpty());
    }

    @Test
    void lumpSumShortensLoanAndSavesInterest() {
        PrepaymentSimulator.Outcome o =
                PrepaymentSimulator.lumpSum(P, RATE, emi(), TENURE, 12, Money.ofPaise(20_000_000L));
        assertTrue(o.newInstalments() < TENURE);
        assertTrue(o.interestSaved().paise() > 0);
        assertEquals(emi(), o.newEmi());
    }

    @Test
    void zeroLumpChangesNothing() {
        PrepaymentSimulator.Outcome o = PrepaymentSimulator.lumpSum(P, RATE, emi(), TENURE, 12, Money.ZERO);
        assertEquals(TENURE, o.newInstalments());
        assertEquals(Money.ZERO, o.interestSaved());
    }

    @Test
    void lumpAtInstalmentZeroSavesMoreThanLaterLump() {
        Money lump = Money.ofPaise(20_000_000L);
        PrepaymentSimulator.Outcome early = PrepaymentSimulator.lumpSum(P, RATE, emi(), TENURE, 0, lump);
        PrepaymentSimulator.Outcome late = PrepaymentSimulator.lumpSum(P, RATE, emi(), TENURE, 60, lump);
        assertTrue(early.interestSaved().paise() > late.interestSaved().paise());
    }

    @Test
    void lumpAtOrAboveBalanceClosesLoanThere() {
        AmortisingLoan.Schedule base = AmortisingLoan.schedule(P, RATE, emi(), TENURE);
        Money balance = base.balanceAfter(24);
        PrepaymentSimulator.Outcome exact = PrepaymentSimulator.lumpSum(P, RATE, emi(), TENURE, 24, balance);
        PrepaymentSimulator.Outcome over = PrepaymentSimulator.lumpSum(P, RATE, emi(), TENURE, 24,
                balance.plus(Money.ofPaise(1_000_000L)));
        assertEquals(24, exact.newInstalments());
        assertEquals(24, over.newInstalments());
        assertEquals(base.interestRemainingAfter(24), exact.interestSaved());
        assertEquals(exact.interestSaved(), over.interestSaved());
    }

    @Test
    void lumpRejectsBadInputs() {
        assertThrows(IllegalArgumentException.class, () -> PrepaymentSimulator.lumpSum(P, RATE, emi(),
                TENURE, -1, Money.ofPaise(1L)));
        assertThrows(IllegalArgumentException.class, () -> PrepaymentSimulator.lumpSum(P, RATE, emi(),
                TENURE, TENURE + 1, Money.ofPaise(1L)));
        assertThrows(IllegalArgumentException.class, () -> PrepaymentSimulator.lumpSum(P, RATE, emi(),
                TENURE, 1, Money.ofPaise(-1L)));
    }

    @Test
    void lumpAfterFinalInstalmentIsNoOp() {
        PrepaymentSimulator.Outcome o = PrepaymentSimulator.lumpSum(P, RATE, emi(), TENURE, TENURE,
                Money.ofPaise(1_000_000L));
        assertEquals(TENURE, o.newInstalments());
        assertEquals(Money.ZERO, o.interestSaved());
    }

    @Test
    void increaseEmiShortensLoan() {
        Money higher = emi().plus(Money.ofPaise(1_000_000L));
        PrepaymentSimulator.Outcome o = PrepaymentSimulator.increaseEmi(P, RATE, emi(), TENURE, higher);
        assertTrue(o.newInstalments() < TENURE);
        assertTrue(o.interestSaved().paise() > 0);
        assertEquals(higher, o.newEmi());
    }

    @Test
    void increaseEmiToSameEmiSavesNothing() {
        PrepaymentSimulator.Outcome o = PrepaymentSimulator.increaseEmi(P, RATE, emi(), TENURE, emi());
        assertEquals(Money.ZERO, o.interestSaved());
        assertEquals(TENURE, o.newInstalments());
    }

    @Test
    void increaseEmiBelowCurrentThrows() {
        assertThrows(IllegalArgumentException.class, () -> PrepaymentSimulator.increaseEmi(P, RATE, emi(),
                TENURE, emi().minus(Money.ofPaise(1L))));
    }

    @Test
    void emiBelowMonthlyInterestThrows() {
        Money tiny = Money.ofPaise(1_000L);
        assertThrows(IllegalArgumentException.class,
                () -> PrepaymentSimulator.increaseEmi(P, RATE, tiny, TENURE, tiny.plus(tiny)));
    }

    @Test
    void payoffCycleTracksInstalmentCountAcrossMonthEnd() {
        LocalDate first = LocalDate.of(2026, 1, 31);
        PrepaymentSimulator.Outcome o = PrepaymentSimulator.closeIn(P, RATE, TENURE, 12);
        CycleCalendar.Cycle base = o.baselinePayoffCycle(first, START_DAY).orElseThrow();
        CycleCalendar.Cycle fast = o.newPayoffCycle(first, START_DAY).orElseThrow();
        assertTrue(base.equals(AmortisingLoan.payoffCycle(AmortisingLoan.schedule(P, RATE, TENURE), first,
                START_DAY).orElseThrow()));
        assertNotEquals(base, fast);
    }

    @Test
    void syntheticCloseMatchesIndependentCalculation() {
        // Recomputed outside Java for the synthetic 10 L, 9%, 120 -> 60 month case.
        PrepaymentSimulator.Outcome o = PrepaymentSimulator.closeIn(P, RATE, TENURE, 60);
        assertEquals(2_075_836L, o.newEmi().paise(), 2L);
        assertEquals(27_460_797L, o.interestSaved().paise(), 120L);
    }

    @Test
    void lumpOfFullPrincipalBeforeFirstInstalmentSavesAllInterest() {
        PrepaymentSimulator.Outcome o = PrepaymentSimulator.lumpSum(P, RATE, emi(), TENURE, 0, P);
        assertEquals(o.baselineInterest(), o.interestSaved());
    }
}
