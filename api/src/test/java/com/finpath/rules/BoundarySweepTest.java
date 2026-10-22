package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * T27 boundary sweep: one named test per boundary case, per rule family. Amounts are synthetic.
 * Expected values come from the rules as they behave today; nothing here moves a threshold.
 */
class BoundarySweepTest {

    private static final RuleConfig CFG = RuleConfig.defaults();
    private static final int START_DAY = 25;
    private static final Money INCOME = Money.ofPaise(10_000_000L);
    private static final Money BUDGET = Money.ofPaise(1_000_000L);
    private static final BigDecimal RATE = new BigDecimal("0.12");
    private static final LocalDate FIRST_DUE = LocalDate.of(2026, 1, 25);

    private static AmortisingLoan.Schedule twelveMonths() {
        return AmortisingLoan.schedule(Money.ofPaise(1_200_000L), RATE, 12);
    }

    // ---- zero principal ----

    @Test
    void zeroPrincipalAmortisingHasZeroEmiAndNoSchedule() {
        assertEquals(0L, AmortisingLoan.emi(Money.ZERO, RATE, 12).paise());
        assertEquals(0, AmortisingLoan.schedule(Money.ZERO, RATE, 12).size());
    }

    @Test
    void zeroPrincipalBulletHasZeroRateAndZeroInterest() {
        Loan.Bullet l = new Loan.Bullet(Money.ZERO, Money.ofPaise(1_000L), LocalDate.of(2030, 3, 25));
        assertEquals(0, BulletLoan.effectiveAnnualRate(l).signum());
        assertEquals(0L, BulletLoan.interestToDueDate(l, LocalDate.of(2029, 1, 1), START_DAY).paise());
    }

    @Test
    void zeroPrincipalSinkingFundIsFundedWithNothingToSetAside() {
        SinkingFund.Result r = SinkingFund.evaluate(Money.ZERO, Money.ZERO, FIRST_DUE,
                LocalDate.of(2027, 1, 25), LocalDate.of(2026, 6, 1), START_DAY);
        assertEquals(SinkingFund.Status.FUNDED, r.status());
        assertEquals(0L, r.setAsidePerCycle().paise());
        assertEquals(0L, r.catchUp().paise());
    }

    // ---- final instalment ----

    @Test
    void finalInstalmentClosesBalanceAtExactlyZeroAndAllPrincipalIsRepaid() {
        AmortisingLoan.Schedule s = twelveMonths();
        assertEquals(12, s.size());
        AmortisingLoan.Instalment last = s.instalments().get(11);
        assertEquals(12, last.number());
        assertEquals(0L, last.closing().paise());
        assertEquals(1_200_000L, s.totalPrincipal().paise());
        assertEquals(0L, s.balanceAfter(12).paise());
        assertEquals(0L, s.interestRemainingAfter(12).paise());
    }

    @Test
    void finalInstalmentCycleIsThePayoffCycle() {
        Optional<CycleCalendar.Cycle> c = AmortisingLoan.payoffCycle(twelveMonths(), FIRST_DUE, START_DAY);
        assertEquals(LocalDate.of(2026, 12, 25), c.orElseThrow().start());
        assertEquals(LocalDate.of(2027, 1, 25), c.orElseThrow().endExclusive());
    }

    // ---- rate at threshold ----

    @Test
    void emiRatioExactly40PercentIsOkAndJustOverIsWarn() {
        // 40.00% of 100,000 rupees is 40,000 rupees; 40.01% is 40,010 rupees.
        assertEquals(EmiGuard.Status.OK,
                EmiGuard.evaluate(Money.ofPaise(4_000_000L), INCOME, CFG).status());
        assertEquals(EmiGuard.Status.WARN,
                EmiGuard.evaluate(Money.ofPaise(4_001_000L), INCOME, CFG).status());
    }

    @Test
    void emiRatioExactly50PercentIsWarnAndJustOverIsBlock() {
        assertEquals(EmiGuard.Status.WARN,
                EmiGuard.evaluate(Money.ofPaise(5_000_000L), INCOME, CFG).status());
        assertEquals(EmiGuard.Status.BLOCK,
                EmiGuard.evaluate(Money.ofPaise(5_001_000L), INCOME, CFG).status());
    }

    @Test
    void budgetAt80PercentWarnsAndJustUnderDoesNot() {
        assertEquals(BudgetRules.Level.OK,
                BudgetRules.levelFor(BUDGET, Money.ofPaise(799_999L), CFG));
        assertEquals(BudgetRules.Level.WARN,
                BudgetRules.levelFor(BUDGET, Money.ofPaise(800_000L), CFG));
        BudgetRules.Status st = BudgetRules.status(BUDGET, Money.ofPaise(800_000L), CFG);
        assertEquals(0, new BigDecimal("0.8").compareTo(st.usedFraction()));
    }

    @Test
    void budgetAt100PercentBreachesAndJustUnderOnlyWarns() {
        assertEquals(BudgetRules.Level.WARN,
                BudgetRules.levelFor(BUDGET, Money.ofPaise(999_999L), CFG));
        assertEquals(BudgetRules.Level.BREACH,
                BudgetRules.levelFor(BUDGET, Money.ofPaise(1_000_000L), CFG));
    }

    // ---- cycle spanning month end ----

    @Test
    void cycleSpanningMonthEndContainsLastDayOfMonthAndNextMonthsFirstDays() {
        CycleCalendar.Cycle c = CycleCalendar.cycleFor(LocalDate.of(2026, 1, 31), START_DAY);
        assertEquals(LocalDate.of(2026, 1, 25), c.start());
        assertEquals(LocalDate.of(2026, 2, 25), c.endExclusive());
        assertEquals(31, c.lengthDays());
        assertTrue(c.contains(LocalDate.of(2026, 2, 1)));
        assertEquals(c, CycleCalendar.cycleFor(LocalDate.of(2026, 2, 24), START_DAY));
        assertFalse(c.contains(LocalDate.of(2026, 2, 25)));
    }

    @Test
    void cycleSpanningMonthEndWithStartDay31ClampsToFebruaryEnd() {
        CycleCalendar.Cycle c = CycleCalendar.cycleFor(LocalDate.of(2026, 2, 28), 31);
        assertEquals(LocalDate.of(2026, 2, 28), c.start());
        assertEquals(LocalDate.of(2026, 3, 31), c.endExclusive());
    }

    @Test
    void cycleSpanningMonthEndCountsOneCycleBetweenDatesEitherSideOfTheStart() {
        assertEquals(1, CycleCalendar.cyclesBetween(LocalDate.of(2026, 1, 24), LocalDate.of(2026, 1, 25), START_DAY));
        assertEquals(0, CycleCalendar.cyclesBetween(LocalDate.of(2026, 1, 25), LocalDate.of(2026, 2, 24), START_DAY));
    }

    // ---- already-closed loan ----

    @Test
    void alreadyClosedAmortisingLoanHasNothingLeftAndStaysClosed() {
        AmortisingLoan.Schedule s = twelveMonths();
        LocalDate firstCycleAfterPayoff = LocalDate.of(2027, 1, 25);
        assertTrue(AmortisingLoan.isClosed(s, FIRST_DUE, firstCycleAfterPayoff, START_DAY));
        assertEquals(12, AmortisingLoan.instalmentsPaid(s, FIRST_DUE, LocalDate.of(2035, 1, 1), START_DAY));
        assertEquals(0L, AmortisingLoan.outstandingPrincipal(s, FIRST_DUE, firstCycleAfterPayoff, START_DAY).paise());
        assertEquals(0L, AmortisingLoan.interestRemaining(s, FIRST_DUE, firstCycleAfterPayoff, START_DAY).paise());
    }

    @Test
    void amortisingLoanOneDayBeforeClosureIsStillOpen() {
        AmortisingLoan.Schedule s = twelveMonths();
        LocalDate lastDayOfPayoffCycle = LocalDate.of(2027, 1, 24);
        assertFalse(AmortisingLoan.isClosed(s, FIRST_DUE, lastDayOfPayoffCycle, START_DAY));
        assertEquals(11, AmortisingLoan.instalmentsPaid(s, FIRST_DUE, lastDayOfPayoffCycle, START_DAY));
        assertTrue(AmortisingLoan.outstandingPrincipal(s, FIRST_DUE, lastDayOfPayoffCycle, START_DAY).paise() > 0);
    }

    @Test
    void alreadyClosedBulletFullySavedIsFundedAndUnfundedPastDueIsPastDue() {
        LocalDate due = LocalDate.of(2026, 3, 25);
        LocalDate after = LocalDate.of(2026, 6, 1);
        Money p = Money.ofPaise(5_000_000L);
        SinkingFund.Result funded = SinkingFund.evaluate(p, p, FIRST_DUE, due, after, START_DAY);
        assertEquals(SinkingFund.Status.FUNDED, funded.status());
        assertEquals(0L, funded.outstanding().paise());
        SinkingFund.Result overdue = SinkingFund.evaluate(p, Money.ZERO, FIRST_DUE, due, after, START_DAY);
        assertEquals(SinkingFund.Status.PAST_DUE, overdue.status());
        assertTrue(overdue.cyclesRemaining() < 0);
        assertEquals(p.paise(), overdue.catchUp().paise());
        assertTrue(BulletLoan.isPastDue(new Loan.Bullet(p, Money.ofPaise(1_000L), due), after, START_DAY));
    }
}
