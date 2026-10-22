package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class BudgetRulesTest {

    private static final RuleConfig CFG = RuleConfig.defaults();
    private static final Money BUDGET = Money.ofPaise(1_000_000L);

    private static BudgetRules.Level level(long spentPaise) {
        return BudgetRules.levelFor(BUDGET, Money.ofPaise(spentPaise), CFG);
    }

    @Test
    void justUnderEightyPercentRaisesNothing() {
        assertEquals(BudgetRules.Level.OK, level(799_999L));
        assertEquals(Optional.empty(), BudgetRules.newAlert(BudgetRules.Level.OK, level(799_999L)));
    }

    @Test
    void exactlyEightyPercentFiresOneWarn() {
        assertEquals(BudgetRules.Level.WARN, level(800_000L));
        assertEquals(Optional.of(BudgetRules.Level.WARN), BudgetRules.newAlert(BudgetRules.Level.OK, level(800_000L)));
        // evaluating again, or spending a little more, does not fire a second WARN
        assertEquals(Optional.empty(), BudgetRules.newAlert(BudgetRules.Level.WARN, level(800_000L)));
        assertEquals(Optional.empty(), BudgetRules.newAlert(BudgetRules.Level.WARN, level(950_000L)));
    }

    @Test
    void hundredPercentFiresBreachWithoutDuplicateWarn() {
        assertEquals(BudgetRules.Level.WARN, level(999_999L));
        assertEquals(BudgetRules.Level.BREACH, level(1_000_000L));
        // jumping straight to 100%: a single BREACH, nothing else
        assertEquals(Optional.of(BudgetRules.Level.BREACH), BudgetRules.newAlert(BudgetRules.Level.OK, level(1_000_000L)));
        // after WARN has fired, 100% adds only BREACH
        assertEquals(Optional.of(BudgetRules.Level.BREACH), BudgetRules.newAlert(BudgetRules.Level.WARN, level(1_000_000L)));
        assertEquals(Optional.empty(), BudgetRules.newAlert(BudgetRules.Level.BREACH, level(1_500_000L)));
    }

    @Test
    void zeroBudgetBreachesOnlyOnceSomethingIsSpent() {
        assertEquals(BudgetRules.Level.OK, BudgetRules.levelFor(Money.ZERO, Money.ZERO, CFG));
        assertEquals(BudgetRules.Level.BREACH, BudgetRules.levelFor(Money.ZERO, Money.ofPaise(1), CFG));
    }

    @Test
    void statusReportsRemainingAndFraction() {
        BudgetRules.Status s = BudgetRules.status(BUDGET, Money.ofPaise(1_250_000L), CFG);
        assertEquals(-250_000L, s.remaining().paise());
        assertEquals(0, s.usedFraction().compareTo(new java.math.BigDecimal("1.25")));
        assertEquals(BudgetRules.Level.BREACH, s.level());
        assertEquals(null, BudgetRules.status(Money.ZERO, Money.ZERO, CFG).usedFraction());
    }

    @Test
    void thresholdsComeFromConfig() {
        RuleConfig strict = new RuleConfig(CFG.emiToIncomeWarn(), CFG.emiToIncomeBlock(), CFG.inflation(),
                CFG.equityReturn(), CFG.debtReturnLow(), CFG.debtReturnHigh(), CFG.fiMultiplier(),
                CFG.minHabitSipPaise(), new java.math.BigDecimal("0.50"), CFG.budgetAlertLimit(),
                CFG.equityMinHorizonYears());
        assertEquals(BudgetRules.Level.WARN, BudgetRules.levelFor(BUDGET, Money.ofPaise(500_000L), strict));
    }

    private static BudgetRules.Spend out(String cat, long paise) {
        return new BudgetRules.Spend(cat, TransactionHash.Direction.OUT, paise);
    }

    @Test
    void spentByCategoryIgnoresInRows() {
        java.util.Map<String, Money> m = BudgetRules.spentByCategory(java.util.List.of(
                new BudgetRules.Spend("FOOD", TransactionHash.Direction.IN, 500L), out("FOOD", 200L)));
        assertEquals(200L, m.get("FOOD").paise());
        assertEquals(java.util.Map.of(), BudgetRules.spentByCategory(java.util.List.of(
                new BudgetRules.Spend("FOOD", TransactionHash.Direction.IN, 500L))));
    }

    @Test
    void spentByCategorySumsAndKeepsCategoriesApart() {
        java.util.Map<String, Money> m = BudgetRules.spentByCategory(
                java.util.List.of(out("FOOD", 200L), out("FOOD", 300L), out("FUEL", 70L)));
        assertEquals(500L, m.get("FOOD").paise());
        assertEquals(70L, m.get("FUEL").paise());
        assertEquals(2, m.size());
        assertEquals(Money.ZERO, m.getOrDefault("RENT", Money.ZERO));
    }

    @Test
    void spentByCategoryEmptyListAndOverflow() {
        assertEquals(java.util.Map.of(), BudgetRules.spentByCategory(java.util.List.of()));
        assertThrows(ArithmeticException.class, () -> BudgetRules.spentByCategory(
                java.util.List.of(out("FOOD", Long.MAX_VALUE), out("FOOD", 1L))));
    }

    @Test
    void sumAddsExactlyAndRejectsOverflowAndNulls() {
        assertEquals(Money.ZERO, BudgetRules.sum(java.util.List.of()));
        assertEquals(Money.ofPaise(600L), BudgetRules.sum(
                java.util.List.of(Money.ofPaise(100L), Money.ofPaise(200L), Money.ofPaise(300L))));
        assertThrows(ArithmeticException.class, () -> BudgetRules.sum(
                java.util.List.of(Money.ofPaise(Long.MAX_VALUE), Money.ofPaise(1L))));
        assertThrows(NullPointerException.class, () -> BudgetRules.sum(java.util.Arrays.asList(Money.ZERO, null)));
    }

    @Test
    void safeToSpendOnDayTenOfThirtyOneDayCycle() {
        // cycle 25 Oct -> 25 Nov is 31 days; day 10 of the cycle is 3 Nov (9 elapsed), 22 days left incl. today
        CycleCalendar.Cycle cycle = CycleCalendar.cycleFor(LocalDate.of(2026, 10, 25), 25);
        LocalDate day10 = LocalDate.of(2026, 11, 3);
        assertEquals(31, cycle.lengthDays());
        assertEquals(22, CycleCalendar.daysLeft(cycle, day10));
        // budget 31,000.00, spent 9,000.00 -> 22,000.00 / 22 = 1,000.00 per day
        Money perDay = BudgetRules.safeToSpendPerDay(Money.ofPaise(3_100_000L), Money.ofPaise(900_000L), cycle, day10);
        assertEquals(100_000L, perDay.paise());
    }

    @Test
    void safeToSpendRoundsDownAndNeverNegative() {
        CycleCalendar.Cycle cycle = CycleCalendar.cycleFor(LocalDate.of(2026, 10, 25), 25);
        LocalDate day10 = LocalDate.of(2026, 11, 3);
        // 2,200,001 / 22 = 100,000.04.. -> 100,000
        assertEquals(100_000L,
                BudgetRules.safeToSpendPerDay(Money.ofPaise(2_200_001L), Money.ZERO, cycle, day10).paise());
        assertEquals(0L, BudgetRules.safeToSpendPerDay(BUDGET, BUDGET, cycle, day10).paise());
        assertEquals(0L, BudgetRules.safeToSpendPerDay(BUDGET, Money.ofPaise(2_000_000L), cycle, day10).paise());
    }

    @Test
    void safeToSpendRejectsDateOutsideCycleAndNegatives() {
        CycleCalendar.Cycle cycle = CycleCalendar.cycleFor(LocalDate.of(2026, 10, 25), 25);
        assertThrows(IllegalArgumentException.class,
                () -> BudgetRules.safeToSpendPerDay(BUDGET, Money.ZERO, cycle, LocalDate.of(2026, 12, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> BudgetRules.safeToSpendPerDay(Money.ofPaise(-1), Money.ZERO, cycle, LocalDate.of(2026, 11, 3)));
    }
}
