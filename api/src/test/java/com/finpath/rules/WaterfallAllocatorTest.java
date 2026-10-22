package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.finpath.rules.WaterfallAllocator.DebtTarget;
import com.finpath.rules.WaterfallAllocator.GoalTarget;
import com.finpath.rules.WaterfallAllocator.Input;
import com.finpath.rules.WaterfallAllocator.Line;
import com.finpath.rules.WaterfallAllocator.Result;
import com.finpath.rules.WaterfallAllocator.Step;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class WaterfallAllocatorTest {

    private static final RuleConfig CFG = RuleConfig.defaults();
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private static Money p(long paise) {
        return Money.ofPaise(paise);
    }

    private static Input input(long income, List<DebtTarget> debts, List<GoalTarget> goals, long efCurrent,
                               BigDecimal share) {
        return new Input(p(income), List.of(p(300_000), p(100_000)), p(200_000), p(efCurrent), 3, debts,
                goals, share);
    }

    private static List<Line> of(Result r, Step step) {
        return r.lines().stream().filter(l -> l.step() == step).toList();
    }

    @Test
    void surplusIsIncomeLessMandatoryOnly() {
        Result r = WaterfallAllocator.allocate(input(1_000_000, List.of(), List.of(), 0, HALF), CFG);
        assertEquals(600_000L, r.surplus().paise());
    }

    @Test
    void linesSumExactlyToSurplus() {
        Result r = WaterfallAllocator.allocate(input(2_345_679, List.of(), List.of(), 0, HALF), CFG);
        assertEquals(r.surplus(), r.total());
    }

    @Test
    void stepsRunInPriorityOrderAndStopWhenMoneyRunsOut() {
        // surplus 600_000: EF one month 200_000, high debt 300_000, EF target gets the last 100_000
        List<DebtTarget> debts = List.of(new DebtTarget("card", new BigDecimal("0.36"), false, p(300_000)));
        Result r = WaterfallAllocator.allocate(input(1_000_000, debts, List.of(), 0, HALF), CFG);
        assertEquals(List.of(Step.EMERGENCY_FUND_ONE_MONTH, Step.HIGH_INTEREST_DEBT,
                Step.EMERGENCY_FUND_TARGET), r.lines().stream().map(Line::step).toList());
        assertEquals(100_000L, of(r, Step.EMERGENCY_FUND_TARGET).get(0).amount().paise());
        assertEquals(r.surplus(), r.total());
    }

    @Test
    void emergencyFundTopUpCountsStep2ThenTargetOnlyForTheRest() {
        Result r = WaterfallAllocator.allocate(input(5_000_000, List.of(), List.of(), 0, HALF), CFG);
        assertEquals(200_000L, of(r, Step.EMERGENCY_FUND_ONE_MONTH).get(0).amount().paise());
        assertEquals(400_000L, of(r, Step.EMERGENCY_FUND_TARGET).get(0).amount().paise());
    }

    @Test
    void fullFundSkipsBothEmergencySteps() {
        Result r = WaterfallAllocator.allocate(input(5_000_000, List.of(), List.of(), 600_000, HALF), CFG);
        assertTrue(of(r, Step.EMERGENCY_FUND_ONE_MONTH).isEmpty());
        assertTrue(of(r, Step.EMERGENCY_FUND_TARGET).isEmpty());
    }

    @Test
    void highDebtIsAvalancheAndBulletSinkingFundComesFirst() {
        List<DebtTarget> debts = List.of(
                new DebtTarget("personal", new BigDecimal("0.15"), false, p(50_000)),
                new DebtTarget("card", new BigDecimal("0.36"), false, p(50_000)),
                new DebtTarget("gold", new BigDecimal("0.09"), true, p(50_000)));
        Result r = WaterfallAllocator.allocate(input(5_000_000, debts, List.of(), 0, HALF), CFG);
        assertEquals(List.of("gold", "card", "personal"),
                of(r, Step.HIGH_INTEREST_DEBT).stream().map(Line::ref).toList());
    }

    @Test
    void rateExactlyAtThresholdIsNotHighInterest() {
        List<DebtTarget> debts = List.of(
                new DebtTarget("edge", RuleConfig.HIGH_INTEREST_DEBT_RATE, false, p(50_000)));
        Result r = WaterfallAllocator.allocate(input(5_000_000, debts, List.of(), 600_000, BigDecimal.ONE), CFG);
        assertTrue(of(r, Step.HIGH_INTEREST_DEBT).isEmpty());
        assertEquals(50_000L, of(r, Step.DEBT_PREPAYMENT).get(0).amount().paise());
    }

    @Test
    void onlySafeGoalsAreFundedInStep5() {
        List<GoalTarget> goals = List.of(
                new GoalTarget("equityGoal", p(70_000), DatedGoal.InstrumentClass.EQUITY_ALLOWED),
                new GoalTarget("safeGoal", p(80_000), DatedGoal.InstrumentClass.SAFE_ONLY));
        Result r = WaterfallAllocator.allocate(input(5_000_000, List.of(), goals, 600_000, HALF), CFG);
        List<Line> lines = of(r, Step.SAFE_GOALS);
        assertEquals(1, lines.size());
        assertEquals("safeGoal", lines.get(0).ref());
    }

    @Test
    void habitSipIsCappedByWhatIsLeft() {
        // surplus 600_000, EF 200_000 + 400_000 target leaves nothing
        Result r = WaterfallAllocator.allocate(input(1_000_000, List.of(), List.of(), 0, HALF), CFG);
        assertTrue(of(r, Step.HABIT_SIP).isEmpty());
        Result rich = WaterfallAllocator.allocate(input(5_000_000, List.of(), List.of(), 600_000, HALF), CFG);
        assertEquals(CFG.minHabitSipPaise(), of(rich, Step.HABIT_SIP).get(0).amount().paise());
    }

    @Test
    void remainderSplitsByRatioBetweenPrepaymentAndSip() {
        List<DebtTarget> debts = List.of(new DebtTarget("car", new BigDecimal("0.09"), false, p(10_000_000)));
        Result r = WaterfallAllocator.allocate(input(5_000_000, debts, List.of(), 600_000, HALF), CFG);
        long remainder = r.surplus().paise() - CFG.minHabitSipPaise();
        assertEquals(remainder / 2, of(r, Step.DEBT_PREPAYMENT).get(0).amount().paise());
        assertEquals(remainder - remainder / 2, of(r, Step.LONG_TERM_SIP).get(0).amount().paise());
        assertEquals(r.surplus(), r.total());
    }

    @Test
    void prepaymentIsCappedAtOutstandingAndTheRestGoesToSip() {
        List<DebtTarget> debts = List.of(new DebtTarget("small", new BigDecimal("0.09"), false, p(1_000)));
        Result r = WaterfallAllocator.allocate(input(5_000_000, debts, List.of(), 600_000, BigDecimal.ONE), CFG);
        assertEquals(1_000L, of(r, Step.DEBT_PREPAYMENT).get(0).amount().paise());
        assertEquals(r.surplus(), r.total());
    }

    @Test
    void zeroShareSendsEverythingToSip() {
        List<DebtTarget> debts = List.of(new DebtTarget("car", new BigDecimal("0.09"), false, p(10_000_000)));
        Result r = WaterfallAllocator.allocate(input(5_000_000, debts, List.of(), 600_000, BigDecimal.ZERO), CFG);
        assertTrue(of(r, Step.DEBT_PREPAYMENT).isEmpty());
    }

    @Test
    void negativeSurplusAllocatesNothingAndReportsShortfall() {
        Result r = WaterfallAllocator.allocate(input(300_000, List.of(), List.of(), 0, HALF), CFG);
        assertEquals(-100_000L, r.surplus().paise());
        assertEquals(100_000L, r.shortfall().paise());
        assertTrue(r.lines().isEmpty());
    }

    @Test
    void zeroSurplusAllocatesNothing() {
        Result r = WaterfallAllocator.allocate(input(400_000, List.of(), List.of(), 0, HALF), CFG);
        assertEquals(Money.ZERO, r.shortfall());
        assertTrue(r.lines().isEmpty());
    }

    @Test
    void defaultPrepayShareIsThirtyPercent() {
        assertEquals(0, new BigDecimal("0.30").compareTo(RuleConfig.DEFAULT_PREPAY_SHARE));
    }

    @Test
    void twoLowRateDebtsShareThePrepaymentAvalancheFirst() {
        List<DebtTarget> debts = List.of(
                new DebtTarget("b", new BigDecimal("0.08"), false, p(1_000_000)),
                new DebtTarget("a", new BigDecimal("0.10"), false, p(5_000)));
        Result r = WaterfallAllocator.allocate(input(5_000_000, debts, List.of(), 600_000, HALF), CFG);
        List<Line> pre = of(r, Step.DEBT_PREPAYMENT);
        assertEquals("a", pre.get(0).ref());
        assertEquals(5_000L, pre.get(0).amount().paise());
        assertEquals("b", pre.get(1).ref());
        assertEquals(r.surplus(), r.total());
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class,
                () -> input(1_000_000, List.of(), List.of(), 0, new BigDecimal("1.1")));
        assertThrows(IllegalArgumentException.class,
                () -> new Input(p(1), List.of(), p(1), p(0), 0, List.of(), List.of(), HALF));
        assertThrows(IllegalArgumentException.class,
                () -> new DebtTarget("x", BigDecimal.ONE.negate(), false, p(1)));
    }
}
