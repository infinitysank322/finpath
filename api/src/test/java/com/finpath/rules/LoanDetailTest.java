package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class LoanDetailTest {

    private static final int START_DAY = 25;
    private static final LocalDate REF = LocalDate.of(2026, 10, 25);
    private static final Money P = Money.ofPaise(100_000_000L);
    private static final BigDecimal RATE = new BigDecimal("0.08");

    private static LoanDetail.Result emi(Money principal, BigDecimal rate, Money stated, Integer left) {
        return LoanDetail.compute(LoanType.EMI, principal, rate, stated, left, null, null, REF, START_DAY);
    }

    @Test
    void statedEmiDrivesScheduleAndInterestNotTheComputedEmi() {
        Money computed = AmortisingLoan.emi(P, RATE, 120);
        Money stated = computed.plus(Money.ofPaise(50_000L));
        LoanDetail.Result r = emi(P, RATE, stated, 120);

        assertEquals(stated, r.emiUsed());
        assertFalse(r.emiDerived());
        assertEquals(computed, r.computedEmi().orElseThrow());
        assertEquals(AmortisingLoan.schedule(P, RATE, stated, 120).totalInterest(), r.interestRemaining());
        assertNotEquals(AmortisingLoan.schedule(P, RATE, computed, 120).totalInterest(), r.interestRemaining());
        assertTrue(r.interestRemaining().paise()
                < AmortisingLoan.schedule(P, RATE, 120).totalInterest().paise(), "higher EMI means less interest");
        assertTrue(r.instalmentsRemaining() < 120, "higher EMI closes earlier");
        assertEquals(r.instalmentsRemaining(), r.instalments().size());
        assertEquals(0L, r.instalments().get(r.instalments().size() - 1).closing().paise());
        assertEquals(r.interestRemaining().plus(P), r.totalRemainingPayable());
    }

    @Test
    void derivedEmiIsFlaggedAndEqualsComputed() {
        LoanDetail.Result r = emi(P, RATE, null, 120);
        assertTrue(r.emiDerived());
        assertEquals(AmortisingLoan.emi(P, RATE, 120), r.emiUsed());
        assertEquals(r.emiUsed(), r.computedEmi().orElseThrow());
        assertEquals(120, r.instalmentsRemaining());
    }

    @Test
    void payoffCycleCountsReferenceCycleAsFirst() {
        LoanDetail.Result r = emi(P, RATE, null, 12);
        // 12 payments starting in the 25 Oct 2026 cycle end in the cycle starting 25 Sep 2027.
        assertEquals(LocalDate.of(2027, 9, 25), r.payoffCycle().orElseThrow().start());
    }

    @Test
    void cycleSpanningMonthEndStartDay31() {
        LoanDetail.Result r = LoanDetail.compute(LoanType.EMI, P, RATE, null, 3, null, null,
                LocalDate.of(2027, 1, 31), 31);
        assertEquals(LocalDate.of(2027, 3, 31), r.payoffCycle().orElseThrow().start());
    }

    @Test
    void zeroRateIsPrincipalOverTenureWithNoInterest() {
        LoanDetail.Result r = emi(Money.ofPaise(120_000L), BigDecimal.ZERO, null, 12);
        assertEquals(10_000L, r.emiUsed().paise());
        assertEquals(0L, r.interestRemaining().paise());
        assertEquals(0, r.effectiveAnnualRate().signum());
        assertEquals(12, r.instalmentsRemaining());
    }

    @Test
    void finalInstalmentAbsorbsResidueAndIsTheOnlyOneLeft() {
        LoanDetail.Result r = emi(Money.ofPaise(1_000_000L), RATE, Money.ofPaise(5_000_000L), 1);
        assertEquals(1, r.instalmentsRemaining());
        AmortisingLoan.Instalment only = r.instalments().get(0);
        assertEquals(1_000_000L, only.principal().paise());
        assertEquals(0L, only.closing().paise());
        assertEquals(only.payment(), r.totalRemainingPayable());
        assertEquals(REF, r.payoffCycle().orElseThrow().start());
    }

    @Test
    void lastInstalmentOfStatedScheduleIsSmallerWhenEmiOverpays() {
        Money stated = AmortisingLoan.emi(P, RATE, 60);
        LoanDetail.Result r = emi(P, RATE, stated, 120);
        assertTrue(r.instalmentsRemaining() <= 60 + 1);
        assertTrue(r.instalments().get(r.instalments().size() - 1).payment().paise() <= stated.paise() + 1);
    }

    @Test
    void zeroPrincipalIsClosed() {
        LoanDetail.Result r = emi(Money.ZERO, RATE, Money.ofPaise(100L), 5);
        assertTrue(r.closed());
        assertEquals(0, r.instalmentsRemaining());
        assertTrue(r.payoffCycle().isEmpty());
        assertEquals(0L, r.interestRemaining().paise());
        assertEquals(0L, r.totalRemainingPayable().paise());
    }

    @Test
    void statedEmiBelowMonthlyInterestIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> emi(P, RATE, Money.ofPaise(100L), 120));
    }

    @Test
    void invalidInputsRejected() {
        assertThrows(IllegalArgumentException.class, () -> emi(P, RATE, null, null));
        assertThrows(IllegalArgumentException.class, () -> emi(P, RATE, null, 0));
        assertThrows(IllegalArgumentException.class, () -> emi(P, RATE, Money.ZERO, 12));
        assertThrows(IllegalArgumentException.class, () -> emi(P, new BigDecimal("-0.01"), null, 12));
        assertThrows(IllegalArgumentException.class, () -> emi(Money.ofPaise(-1L), RATE, null, 12));
        assertThrows(NullPointerException.class, () -> LoanDetail.compute(LoanType.BULLET, P, RATE, null, null,
                null, null, REF, START_DAY));
    }

    // ---- bullet ----

    private static LoanDetail.Result bullet(Money principal, Money stated, LocalDate due) {
        return LoanDetail.compute(LoanType.BULLET, principal, new BigDecimal("0.15"), stated, null, due, null, REF,
                START_DAY);
    }

    @Test
    void bulletEffectiveRateFromStatedMonthlyInterest() {
        // 10,000 x 12 / 10,00,000 = 12%, although the 15% rate would derive 12,500
        LoanDetail.Result r = bullet(P, Money.ofPaise(1_000_000L), LocalDate.of(2027, 1, 25));
        assertFalse(r.emiDerived());
        assertEquals(new BigDecimal("0.1200000000"), r.effectiveAnnualRate());
        assertEquals(1_250_000L, r.computedEmi().orElseThrow().paise());
        // Oct, Nov, Dec, Jan cycles
        assertEquals(4, r.instalmentsRemaining());
        assertEquals(4_000_000L, r.interestRemaining().paise());
        assertEquals(P.plus(Money.ofPaise(4_000_000L)), r.totalRemainingPayable());
        assertEquals(LocalDate.of(2027, 1, 25), r.payoffCycle().orElseThrow().start());
        assertEquals(P, r.instalments().get(3).principal());
        assertEquals(0L, r.instalments().get(0).principal().paise());
        assertEquals(0L, r.instalments().get(3).closing().paise());
    }

    @Test
    void bulletDerivedInterestWhenNotStated() {
        LoanDetail.Result r = bullet(P, null, LocalDate.of(2026, 10, 30));
        assertTrue(r.emiDerived());
        assertEquals(BulletLoan.monthlyInterest(P, new BigDecimal("0.15")), r.emiUsed());
        assertEquals(new BigDecimal("0.1500000000"), r.effectiveAnnualRate());
        assertEquals(1, r.instalmentsRemaining(), "due in the reference cycle: final month only");
    }

    @Test
    void bulletPastDueHasNothingFurtherAccruing() {
        LoanDetail.Result r = bullet(P, Money.ofPaise(100_000L), LocalDate.of(2026, 9, 1));
        assertTrue(r.pastDue());
        assertFalse(r.closed());
        assertEquals(0, r.instalmentsRemaining());
        assertEquals(0L, r.interestRemaining().paise());
        assertTrue(r.instalments().isEmpty());
    }

    @Test
    void bulletPastDueStillOwesThePrincipal() {
        LoanDetail.Result r = bullet(P, Money.ofPaise(100_000L), LocalDate.of(2026, 9, 1));
        assertEquals(P.paise(), r.totalRemainingPayable().paise());
    }

    @Test
    void slightlyUnderComputedStatedEmiFlagsBalloonFinalPayment() {
        Money computed = AmortisingLoan.emi(P, RATE, 120);
        LoanDetail.Result under = emi(P, RATE, Money.ofPaise(computed.paise() - 100L), 120);
        assertTrue(under.balloonFinalPayment());
        assertTrue(under.instalments().get(119).payment().paise() > under.emiUsed().paise());
        assertFalse(emi(P, RATE, null, 120).balloonFinalPayment());
        assertFalse(emi(P, RATE, Money.ofPaise(computed.paise() + 100L), 120).balloonFinalPayment());
        assertFalse(bullet(P, Money.ofPaise(100_000L), LocalDate.of(2027, 1, 25)).balloonFinalPayment());
    }

    @Test
    void tenureAndBulletHorizonAreCapped() {
        assertEquals(LoanDetail.MAX_EMIS_LEFT, emi(P, RATE, null, LoanDetail.MAX_EMIS_LEFT).instalmentsRemaining());
        assertThrows(IllegalArgumentException.class, () -> emi(P, RATE, null, LoanDetail.MAX_EMIS_LEFT + 1));
        LocalDate limit = REF.plusYears(LoanDetail.MAX_BULLET_YEARS);
        assertEquals(limit, bullet(P, Money.ofPaise(100_000L), limit).payoffCycle().isPresent() ? limit : null);
        assertThrows(IllegalArgumentException.class, () -> bullet(P, Money.ofPaise(100_000L), limit.plusDays(1)));
        assertTrue(LoanDetail.dueWithinHorizon(limit, REF));
        assertFalse(LoanDetail.dueWithinHorizon(limit.plusDays(1), REF));
    }

    @Test
    void bulletZeroPrincipalIsClosed() {
        LoanDetail.Result r = bullet(Money.ZERO, Money.ofPaise(100_000L), LocalDate.of(2027, 1, 25));
        assertTrue(r.closed());
        assertEquals(0, r.effectiveAnnualRate().signum());
        assertEquals(0L, r.interestRemaining().paise());
        assertTrue(r.payoffCycle().isEmpty());
    }

    @Test
    void bulletIsNotAmortised() {
        LoanDetail.Result r = bullet(P, Money.ofPaise(100_000L), LocalDate.of(2027, 1, 25));
        assertEquals(P, r.instalments().get(2).closing(), "principal untouched before the due cycle");
    }

    // ---- card EMI ----

    private static LoanDetail.Result card(Money principal, Money inst, int left, Money fee) {
        return LoanDetail.compute(LoanType.CARD_EMI, principal, BigDecimal.ZERO, inst, left, null, fee, REF,
                START_DAY);
    }

    @Test
    void cardEmiCostsSpreadAndFeeShowsInEffectiveRate() {
        LoanDetail.Result r = card(Money.ofPaise(120_000L), Money.ofPaise(10_000L), 12, Money.ofPaise(2_000L));
        assertTrue(r.computedEmi().isEmpty());
        assertFalse(r.emiDerived());
        assertEquals(0L, r.interestRemaining().paise());
        assertTrue(r.effectiveAnnualRate().signum() > 0, "zero-interest EMI with a fee still has a cost");
        assertEquals(12, r.instalments().size());
        assertEquals(0L, r.instalments().get(11).closing().paise());
        assertEquals(Money.ofPaise(120_000L), r.totalRemainingPayable());
    }

    @Test
    void cardEmiWithInterestAndRequiredStatedInstalment() {
        LoanDetail.Result r = card(Money.ofPaise(120_000L), Money.ofPaise(10_500L), 12, null);
        assertEquals(6_000L, r.interestRemaining().paise());
        long sum = r.instalments().stream().mapToLong(i -> i.interest().paise()).sum();
        assertEquals(6_000L, sum);
        assertThrows(IllegalArgumentException.class, () -> card(Money.ofPaise(120_000L), null, 12, null));
        assertThrows(IllegalArgumentException.class,
                () -> card(Money.ofPaise(120_000L), Money.ofPaise(1_000L), 12, null));
    }

    @Test
    void cardEmiZeroPrincipalIsClosed() {
        LoanDetail.Result r = card(Money.ZERO, Money.ofPaise(10_000L), 3, null);
        assertTrue(r.closed());
        assertEquals(0, r.instalmentsRemaining());
    }
}
