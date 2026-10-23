package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FiPlannerTest {

    private static final RuleConfig CFG = RuleConfig.defaults();
    private static final Money EXPENSE = Money.ofPaise(10_000_000L);
    private static final Money PASSIVE = Money.ofPaise(2_000_000L);
    private static final BigDecimal STEP = new BigDecimal("0.10");

    @Test
    void bandRatesAreOrderedFromConfig() {
        BigDecimal low = FiPlanner.bandRate(FiPlanner.Band.LOW, CFG);
        BigDecimal base = FiPlanner.bandRate(FiPlanner.Band.BASE, CFG);
        BigDecimal high = FiPlanner.bandRate(FiPlanner.Band.HIGH, CFG);
        assertEquals(CFG.debtReturnHigh(), low);
        assertEquals(CFG.equityReturn(), high);
        assertTrue(low.compareTo(base) < 0 && base.compareTo(high) < 0);
    }

    @Test
    void lumpFutureIsMonthlyCompounding() {
        assertEquals(1_000_000L, FiPlanner.lumpFuture(Money.ofPaise(1_000_000L), CFG.equityReturn(), 0).paise());
        assertEquals(0L, FiPlanner.lumpFuture(Money.ZERO, CFG.equityReturn(), 120).paise());
        long one = FiPlanner.lumpFuture(Money.ofPaise(1_200_000L), new BigDecimal("0.12"), 1).paise();
        assertEquals(1_212_000L, one);
    }

    @Test
    void requiredSipReachesNetCorpusInEveryBandAndHigherReturnNeedsLess() {
        FiPlanner.Plan p = FiPlanner.plan(EXPENSE, PASSIVE, Money.ZERO, Money.ZERO, STEP, 15, 40, CFG);
        for (FiPlanner.BandResult b : p.bands()) {
            Money fv = FiPlanner.savingsAfter(Money.ZERO, b.requiredFirstMonthly(), b.annualReturn(), STEP, 15);
            assertTrue(fv.paise() >= p.corpus().netCorpus().paise());
        }
        long low = p.bands().get(0).requiredFirstMonthly().paise();
        long base = p.bands().get(1).requiredFirstMonthly().paise();
        long high = p.bands().get(2).requiredFirstMonthly().paise();
        assertTrue(low > base && base > high);
    }

    @Test
    void openingBalanceLowersTheRequiredSip() {
        long without = FiPlanner.plan(EXPENSE, PASSIVE, Money.ZERO, Money.ZERO, STEP, 15, 40, CFG)
                .bands().get(1).requiredFirstMonthly().paise();
        long with = FiPlanner.plan(EXPENSE, PASSIVE, Money.ofPaise(500_000_000L), Money.ZERO, STEP, 15, 40, CFG)
                .bands().get(1).requiredFirstMonthly().paise();
        assertTrue(with < without);
    }

    @Test
    void hugeOpeningBalanceNeedsNoSip() {
        FiPlanner.Plan p = FiPlanner.plan(EXPENSE, PASSIVE, Money.ofPaise(9_000_000_000L), Money.ZERO, STEP, 5, 40,
                CFG);
        p.bands().forEach(b -> assertEquals(0L, b.requiredFirstMonthly().paise()));
        assertEquals(0, p.bands().get(0).fiYears().getAsInt());
    }

    @Test
    void fiYearsShrinkWithHigherReturnAndAreEmptyWhenNeverReached() {
        FiPlanner.Plan p = FiPlanner.plan(EXPENSE, PASSIVE, Money.ZERO, Money.ofPaise(5_000_000L), STEP, 15, 40, CFG);
        int low = p.bands().get(0).fiYears().orElse(Integer.MAX_VALUE);
        int high = p.bands().get(2).fiYears().orElse(Integer.MAX_VALUE);
        assertTrue(high <= low);
        FiPlanner.Plan none = FiPlanner.plan(EXPENSE, PASSIVE, Money.ZERO, Money.ZERO, BigDecimal.ZERO, 15, 40, CFG);
        none.bands().forEach(b -> assertFalse(b.fiYears().isPresent()));
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class,
                () -> FiPlanner.plan(EXPENSE, PASSIVE, Money.ZERO, Money.ZERO, STEP, 0, 40, CFG));
        assertThrows(IllegalArgumentException.class,
                () -> FiPlanner.plan(EXPENSE, PASSIVE, Money.ZERO, Money.ZERO, STEP, 10, 5, CFG));
        assertThrows(IllegalArgumentException.class, () -> FiPlanner.lumpFuture(Money.ofPaise(-1), STEP, 1));
    }
}
