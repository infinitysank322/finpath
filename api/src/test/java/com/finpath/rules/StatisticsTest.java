package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class StatisticsTest {

    @Test
    void savingsRateIsSurplusOverIncomeToOneDecimal() {
        assertEquals(new BigDecimal("40.0"), Statistics.savingsRate(Money.ofPaise(40), Money.ofPaise(100)));
        assertEquals(new BigDecimal("33.3"), Statistics.savingsRate(Money.ofPaise(1), Money.ofPaise(3)));
        assertEquals(new BigDecimal("0.0"), Statistics.savingsRate(Money.ZERO, Money.ofPaise(100)));
    }

    @Test
    void savingsRateRoundsHalfUp() {
        // 0.05 percent exactly: 1 / 2000 -> 0.1 (half up)
        assertEquals(new BigDecimal("0.1"), Statistics.savingsRate(Money.ofPaise(1), Money.ofPaise(2000)));
    }

    @Test
    void negativeSurplusGivesNegativeRate() {
        assertEquals(new BigDecimal("-10.0"), Statistics.savingsRate(Money.ofPaise(-10), Money.ofPaise(100)));
    }

    @Test
    void zeroIncomeOrOutflowHasNoRatio() {
        assertNull(Statistics.savingsRate(Money.ofPaise(5), Money.ZERO));
        assertNull(Statistics.discretionaryShare(Money.ofPaise(5), Money.ZERO));
    }

    @Test
    void discretionaryShareIsPartOverTotal() {
        assertEquals(new BigDecimal("25.0"), Statistics.discretionaryShare(Money.ofPaise(25), Money.ofPaise(100)));
        assertEquals(new BigDecimal("0.0"), Statistics.discretionaryShare(Money.ZERO, Money.ofPaise(100)));
        assertEquals(new BigDecimal("100.0"), Statistics.discretionaryShare(Money.ofPaise(100), Money.ofPaise(100)));
    }

    @Test
    void ratioRejectsNegativeInputs() {
        assertThrows(IllegalArgumentException.class, () -> Statistics.savingsRate(Money.ofPaise(1), Money.ofPaise(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> Statistics.discretionaryShare(Money.ofPaise(-1), Money.ofPaise(100)));
        assertThrows(NullPointerException.class, () -> Statistics.savingsRate(null, Money.ofPaise(1)));
    }

    @Test
    void debtFreeCycleIsLatestPayoff() {
        assertEquals(OptionalInt.of(7), Statistics.debtFreeCycle(List.of(3, 7, 5)));
        assertEquals(OptionalInt.of(0), Statistics.debtFreeCycle(List.of(0)));
    }

    @Test
    void debtFreeCycleEmptyWithNoDebts() {
        assertTrue(Statistics.debtFreeCycle(List.<Integer>of()).isEmpty());
    }

    @Test
    void debtFreeCycleFromPlan() {
        PayoffPlanner.Plan plan = new PayoffPlanner.Plan(PayoffPlanner.Strategy.AVALANCHE, List.of("a", "b"),
                Map.of("a", 4, "b", 9), Money.ZERO, Money.ZERO, 10);
        assertEquals(OptionalInt.of(9), Statistics.debtFreeCycle(plan));
    }

    @Test
    void debtFreeCycleRejectsNegative() {
        assertThrows(IllegalArgumentException.class, () -> Statistics.debtFreeCycle(List.of(-1)));
    }

    @Test
    void netWorthIsAssetsMinusLiabilities() {
        assertEquals(60L, Statistics.netWorth(Money.ofPaise(100), Money.ofPaise(40)).paise());
        assertEquals(0L, Statistics.netWorth(Money.ofPaise(100), Money.ofPaise(100)).paise());
    }

    @Test
    void netWorthMayBeNegative() {
        assertEquals(-70L, Statistics.netWorth(Money.ofPaise(30), Money.ofPaise(100)).paise());
        assertEquals(0L, Statistics.netWorth(Money.ZERO, Money.ZERO).paise());
    }

    @Test
    void netWorthRejectsNegativeInputs() {
        assertThrows(IllegalArgumentException.class, () -> Statistics.netWorth(Money.ofPaise(-1), Money.ZERO));
        assertThrows(IllegalArgumentException.class, () -> Statistics.netWorth(Money.ZERO, Money.ofPaise(-1)));
    }

    @Test
    void streakCountsTrailingRun() {
        assertEquals(3, Statistics.consistencyStreak(List.of(false, true, true, true)));
        assertEquals(2, Statistics.consistencyStreak(List.of(true, false, true, true)));
        assertEquals(4, Statistics.consistencyStreak(List.of(true, true, true, true)));
    }

    @Test
    void missedLatestCycleResetsStreakToZeroNotOne() {
        assertEquals(0, Statistics.consistencyStreak(List.of(true, true, true, false)));
        assertEquals(0, Statistics.consistencyStreak(List.of(false)));
    }

    @Test
    void emptyHistoryHasNoStreak() {
        assertEquals(0, Statistics.consistencyStreak(List.of()));
    }

    @Test
    void streakRejectsNullFlag() {
        assertThrows(NullPointerException.class,
                () -> Statistics.consistencyStreak(java.util.Arrays.asList(true, null)));
    }
}
