package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BulletLoanTest {

    private static final int START_DAY = 25;
    private static final Money PRINCIPAL = Money.ofPaise(20_000_000L);
    private static final Money INTEREST = Money.ofPaise(250_000L);

    private static Loan.Bullet bullet(LocalDate due) {
        return new Loan.Bullet(PRINCIPAL, INTEREST, due);
    }

    @Test
    void effectiveRateIsMonthlyInterestTimesTwelveOverPrincipal() {
        // 2,500 x 12 / 200,000 = 15%
        assertEquals(0, new BigDecimal("0.15").compareTo(
                BulletLoan.effectiveAnnualRate(bullet(LocalDate.of(2030, 3, 25)))));
    }

    @Test
    void effectiveRateRecurringThirdsRoundsAtRateScale() {
        // 4,600 x 12 / 360,000 = 15.3333...% (synthetic amounts, same ratio class as a repeating rate)
        Loan.Bullet l = new Loan.Bullet(Money.ofPaise(36_000_000L), Money.ofPaise(460_000L), LocalDate.of(2030, 1, 1));
        assertEquals(new BigDecimal("0.1533333333"), BulletLoan.effectiveAnnualRate(l));
    }

    @Test
    void zeroMonthlyInterestGivesZeroRateAndZeroInterest() {
        Loan.Bullet l = new Loan.Bullet(PRINCIPAL, Money.ZERO, LocalDate.of(2030, 3, 25));
        assertEquals(0, BigDecimal.ZERO.compareTo(BulletLoan.effectiveAnnualRate(l)));
    }

    @Test
    void effectiveRateRoundsHalfUpAtRateScale() {
        Loan.Bullet l = new Loan.Bullet(Money.ofPaise(3L), Money.ofPaise(1L), LocalDate.of(2030, 1, 1));
        // 12 / 3 = 4 exactly; use 7 to force rounding: 12/7 = 1.7142857142857...
        Loan.Bullet m = new Loan.Bullet(Money.ofPaise(7L), Money.ofPaise(1L), LocalDate.of(2030, 1, 1));
        assertEquals(new BigDecimal("4.0000000000"), BulletLoan.effectiveAnnualRate(l));
        assertEquals(new BigDecimal("1.7142857143"), BulletLoan.effectiveAnnualRate(m));
    }

    @Test
    void zeroPrincipalHasZeroRateAndNoInterest() {
        Loan.Bullet l = new Loan.Bullet(Money.ZERO, INTEREST, LocalDate.of(2030, 3, 25));
        assertEquals(0, BulletLoan.effectiveAnnualRate(l).signum());
        assertEquals(0L, BulletLoan.interestToDueDate(l, LocalDate.of(2029, 1, 1), START_DAY).paise());
    }

    @Test
    void interestToDueDateCountsEveryCycleIncludingTheDueCycle() {
        // Reference and due in the same cycle: one interest payment.
        assertEquals(250_000L, BulletLoan.interestToDueDate(bullet(LocalDate.of(2030, 3, 30)),
                LocalDate.of(2030, 3, 26), START_DAY).paise());
        // Cycles Oct 2026 .. Mar 2028 span 17 gaps, 18 payments.
        assertEquals(250_000L * 18, BulletLoan.interestToDueDate(bullet(LocalDate.of(2028, 3, 25)),
                LocalDate.of(2026, 10, 25), START_DAY).paise());
    }

    @Test
    void cycleSpanningMonthEndBelongsToThePreviousCycle() {
        // 10 Mar 2030 is still in the cycle that began 25 Feb; due 20 Mar is the same cycle.
        assertEquals(250_000L, BulletLoan.interestToDueDate(bullet(LocalDate.of(2030, 3, 20)),
                LocalDate.of(2030, 3, 10), START_DAY).paise());
        // 24 Mar vs due 25 Mar: next cycle, two payments.
        assertEquals(500_000L, BulletLoan.interestToDueDate(bullet(LocalDate.of(2030, 3, 25)),
                LocalDate.of(2030, 3, 24), START_DAY).paise());
    }

    @Test
    void pastDueOwesNoFurtherInterestAndIsFlagged() {
        Loan.Bullet l = bullet(LocalDate.of(2026, 1, 25));
        LocalDate ref = LocalDate.of(2026, 10, 25);
        assertEquals(0L, BulletLoan.interestToDueDate(l, ref, START_DAY).paise());
        assertTrue(BulletLoan.isPastDue(l, ref, START_DAY));
        assertFalse(BulletLoan.isPastDue(bullet(LocalDate.of(2026, 10, 30)), ref, START_DAY));
    }

    @Test
    void negativeAmountsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new Loan.Bullet(Money.ofPaise(-1L), INTEREST, LocalDate.of(2030, 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> new Loan.Bullet(PRINCIPAL, Money.ofPaise(-1L), LocalDate.of(2030, 1, 1)));
    }

    @Test
    void cardEmiWithoutFeeMatchesAmortisingRate() {
        // Instalment from the amortising EMI at 12%/12 months; IRR must recover 12%.
        Money emi = AmortisingLoan.emi(Money.ofPaise(10_000_000L), new BigDecimal("0.12"), 12);
        Loan.CardEmi c = new Loan.CardEmi(Money.ofPaise(10_000_000L), Money.ZERO, emi, 12);
        BigDecimal rate = BulletLoan.cardEmiEffectiveAnnualRate(c);
        assertTrue(rate.subtract(new BigDecimal("0.12")).abs().compareTo(new BigDecimal("0.0001")) < 0, rate.toString());
    }

    @Test
    void zeroInterestCardEmiWithFeeHasPositiveCost() {
        // 12,000 in 12 x 1,000, fee 300: net 11,700, pays 12,000.
        Loan.CardEmi c = new Loan.CardEmi(Money.ofPaise(1_200_000L), Money.ofPaise(30_000L),
                Money.ofPaise(100_000L), 12);
        assertEquals(30_000L, BulletLoan.cardEmiTotalCost(c).paise());
        BigDecimal rate = BulletLoan.cardEmiEffectiveAnnualRate(c);
        assertTrue(rate.signum() > 0);
        assertTrue(rate.compareTo(new BigDecimal("0.10")) < 0, rate.toString());
    }

    @Test
    void cardEmiEdgeCases() {
        Loan.CardEmi zero = new Loan.CardEmi(Money.ZERO, Money.ZERO, Money.ZERO, 6);
        assertEquals(0, BulletLoan.cardEmiEffectiveAnnualRate(zero).signum());
        Loan.CardEmi free = new Loan.CardEmi(Money.ofPaise(600_000L), Money.ZERO, Money.ofPaise(100_000L), 6);
        assertEquals(0, BulletLoan.cardEmiEffectiveAnnualRate(free).signum());
        assertEquals(0L, BulletLoan.cardEmiTotalCost(free).paise());
        Loan.CardEmi feeEatsAll = new Loan.CardEmi(Money.ofPaise(600_000L), Money.ofPaise(600_000L),
                Money.ofPaise(100_000L), 6);
        assertThrows(IllegalArgumentException.class, () -> BulletLoan.cardEmiEffectiveAnnualRate(feeEatsAll));
        assertThrows(IllegalArgumentException.class,
                () -> new Loan.CardEmi(Money.ofPaise(1L), Money.ZERO, Money.ofPaise(1L), 0));
    }

    @Test
    void amortisingPathRejectsBulletAndCardEmi() {
        Loan b = bullet(LocalDate.of(2030, 3, 25));
        Loan c = new Loan.CardEmi(PRINCIPAL, Money.ZERO, INTEREST, 6);
        for (Loan l : new Loan[] {b, c}) {
            assertThrows(IllegalArgumentException.class, () -> AmortisingLoan.emi(l, 12));
            assertThrows(IllegalArgumentException.class, () -> AmortisingLoan.schedule(l, 12));
            assertThrows(IllegalArgumentException.class, () -> AmortisingLoan.requireAmortising(l));
        }
        assertEquals(LoanType.BULLET, b.type());
        assertEquals(LoanType.CARD_EMI, c.type());
    }

    @Test
    void amortisingPathAcceptsEmiLoanAndMatchesRawEntryPoints() {
        Loan.Emi e = new Loan.Emi(Money.ofPaise(9_876_500L), new BigDecimal("0.12"));
        assertEquals(LoanType.EMI, e.type());
        assertEquals(AmortisingLoan.emi(e.principal(), e.annualRate(), 12).paise(), AmortisingLoan.emi(e, 12).paise());
        assertEquals(12, AmortisingLoan.schedule(e, 12).size());
    }
}
