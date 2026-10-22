package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.finpath.rules.WaterfallAllocator.Line;
import com.finpath.rules.WaterfallAllocator.Step;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class UnplannedExpenseTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 25);
    private static final CycleCalendar.Cycle CYCLE = CycleCalendar.cycleFor(TODAY, 25);

    private static Money p(long paise) {
        return Money.ofPaise(paise);
    }

    private static WaterfallAllocator.Result plan() {
        return new WaterfallAllocator.Result(p(1_000_000), Money.ZERO, List.of(
                new Line(Step.EMERGENCY_FUND_ONE_MONTH, null, p(200_000)),
                new Line(Step.HABIT_SIP, null, p(300_000)),
                new Line(Step.DEBT_PREPAYMENT, "car", p(100_000)),
                new Line(Step.LONG_TERM_SIP, null, p(400_000))));
    }

    private static UnplannedExpense.Result run(long expense) {
        return UnplannedExpense.rebalance(plan(), p(expense), p(3_000_000), p(1_000_000), CYCLE, TODAY);
    }

    @Test
    void lowestPriorityStepAbsorbsFirstAndSafeToSpendFalls() {
        UnplannedExpense.Result r = run(150_000);
        assertEquals(Step.LONG_TERM_SIP, r.absorbed().get(0).step());
        assertEquals(150_000L, r.absorbed().get(0).amount().paise());
        assertTrue(r.safeToSpendAfter().paise() < r.safeToSpendBefore().paise());
        assertEquals(Money.ZERO, r.uncovered());
    }

    @Test
    void largeExpenseSpillsUpwardAndLinesSumToReducedSurplus() {
        UnplannedExpense.Result r = run(600_000);
        assertEquals(List.of(Step.LONG_TERM_SIP, Step.DEBT_PREPAYMENT, Step.HABIT_SIP),
                r.absorbed().stream().map(UnplannedExpense.Absorbed::step).toList());
        assertEquals(400_000L, r.surplusAfter().paise());
        assertEquals(r.surplusAfter().paise(), r.lines().stream().mapToLong(l -> l.amount().paise()).sum());
        assertEquals(Step.HABIT_SIP, r.lines().get(1).step());
        assertEquals(200_000L, r.lines().get(1).amount().paise());
    }

    @Test
    void expenseBeyondAllAllocationsIsUncovered() {
        UnplannedExpense.Result r = run(1_500_000);
        assertEquals(500_000L, r.uncovered().paise());
        assertEquals(Money.ZERO, r.surplusAfter());
        assertTrue(r.lines().isEmpty());
    }

    @Test
    void trimOrderIgnoresCallerLineOrder() {
        var shuffled = new WaterfallAllocator.Result(p(1_000_000), Money.ZERO, List.of(
                new Line(Step.LONG_TERM_SIP, null, p(400_000)),
                new Line(Step.EMERGENCY_FUND_ONE_MONTH, null, p(200_000)),
                new Line(Step.DEBT_PREPAYMENT, "car", p(100_000)),
                new Line(Step.HABIT_SIP, null, p(300_000))));
        UnplannedExpense.Result r = UnplannedExpense.rebalance(shuffled, p(450_000), p(3_000_000), p(1_000_000),
                CYCLE, TODAY);
        assertEquals(List.of(Step.LONG_TERM_SIP, Step.DEBT_PREPAYMENT),
                r.absorbed().stream().map(UnplannedExpense.Absorbed::step).toList());
    }

    @Test
    void safeToSpendIsFlooredPerDayBeforeAndAfter() {
        int days = CycleCalendar.daysLeft(CYCLE, TODAY);
        UnplannedExpense.Result r = run(150_000);
        assertEquals(2_000_000L / days, r.safeToSpendBefore().paise());
        assertEquals(1_850_000L / days, r.safeToSpendAfter().paise());
    }

    @Test
    void expenseBeyondBudgetZeroesSafeToSpend() {
        UnplannedExpense.Result r = UnplannedExpense.rebalance(plan(), p(3_000_000), p(3_000_000), p(1_000_000),
                CYCLE, TODAY);
        assertEquals(Money.ZERO, r.safeToSpendAfter());
    }

    @Test
    void largeMidCycleSpendNamesAbsorberAndAccountsForEveryPaise() {
        var big = new WaterfallAllocator.Result(p(5_000_000), Money.ZERO, List.of(
                new Line(Step.EMERGENCY_FUND_ONE_MONTH, null, p(2_000_000)),
                new Line(Step.HABIT_SIP, null, p(500_000)),
                new Line(Step.LONG_TERM_SIP, null, p(2_500_000))));
        UnplannedExpense.Result r = UnplannedExpense.rebalance(big, p(1_500_000), p(3_000_000), p(1_000_000),
                CYCLE, TODAY);
        assertEquals(Step.LONG_TERM_SIP, r.absorbed().get(0).step());
        long absorbed = r.absorbed().stream().mapToLong(a -> a.amount().paise()).sum();
        assertEquals(1_500_000L, absorbed + r.uncovered().paise());
        assertEquals(3_500_000L, r.surplusAfter().paise());
        assertTrue(r.safeToSpendAfter().paise() < r.safeToSpendBefore().paise());
    }

    @Test
    void zeroExpenseChangesNothing() {
        UnplannedExpense.Result r = run(0);
        assertEquals(plan().lines(), r.lines());
        assertTrue(r.absorbed().isEmpty());
        assertEquals(r.safeToSpendBefore(), r.safeToSpendAfter());
    }

    @Test
    void negativeExpenseRejected() {
        assertThrows(IllegalArgumentException.class, () -> run(-1));
    }

    @Test
    void emptyPlanLeavesWholeExpenseUncovered() {
        var empty = new WaterfallAllocator.Result(Money.ZERO, Money.ZERO, List.of());
        UnplannedExpense.Result r = UnplannedExpense.rebalance(empty, p(5_000), p(3_000_000), p(0), CYCLE, TODAY);
        assertEquals(5_000L, r.uncovered().paise());
        assertEquals(0L, r.surplusAfter().paise());
    }
}
