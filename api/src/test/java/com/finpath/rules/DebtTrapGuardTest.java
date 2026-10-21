package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.finpath.rules.DebtTrapGuard.Status;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** Synthetic figures only; persona amounts live in the golden fixture. */
class DebtTrapGuardTest {

    private static final RuleConfig CFG = RuleConfig.defaults();
    private static final Money INCOME = Money.ofPaise(10_000_000L);

    private static DebtTrapGuard.Result at(long emiPaise) {
        return DebtTrapGuard.evaluate(INCOME, Money.ofPaise(emiPaise), CFG);
    }

    @Test
    void zeroEmiIsOkWithFullHeadroom() {
        DebtTrapGuard.Result r = at(0);
        assertEquals(Status.OK, r.status());
        assertEquals(0, BigDecimal.ZERO.compareTo(r.ratio().orElseThrow()));
        assertEquals(4_000_000L, r.headroom().paise());
    }

    @Test
    void exactlyWarnThresholdIsOkWithZeroHeadroom() {
        DebtTrapGuard.Result r = at(4_000_000L);
        assertEquals(Status.OK, r.status());
        assertEquals(0L, r.headroom().paise());
    }

    @Test
    void onePaiseOverWarnIsWarn() {
        DebtTrapGuard.Result r = at(4_000_001L);
        assertEquals(Status.WARN, r.status());
        assertEquals(0L, r.headroom().paise());
    }

    @Test
    void justBelowWarnIsOkWithOnePaiseHeadroom() {
        DebtTrapGuard.Result r = at(3_999_999L);
        assertEquals(Status.OK, r.status());
        assertEquals(1L, r.headroom().paise());
    }

    @Test
    void exactlyBlockThresholdIsWarn() {
        assertEquals(Status.WARN, at(5_000_000L).status());
    }

    @Test
    void onePaiseOverBlockIsBlock() {
        assertEquals(Status.BLOCK, at(5_000_001L).status());
    }

    @Test
    void ratioIsExactFraction() {
        assertEquals(0, new BigDecimal("0.25").compareTo(at(2_500_000L).ratio().orElseThrow()));
    }

    @Test
    void awkwardIncomeComparesExactlyNotOnRoundedRatio() {
        // 7 paise income: 40% = 2.8, 50% = 3.5, so 2 is OK, 3 is WARN, 4 is BLOCK.
        Money income = Money.ofPaise(7L);
        assertEquals(Status.OK, DebtTrapGuard.evaluate(income, Money.ofPaise(2L), CFG).status());
        assertEquals(Status.WARN, DebtTrapGuard.evaluate(income, Money.ofPaise(3L), CFG).status());
        assertEquals(Status.BLOCK, DebtTrapGuard.evaluate(income, Money.ofPaise(4L), CFG).status());
        assertEquals(0L, DebtTrapGuard.evaluate(income, Money.ofPaise(2L), CFG).headroom().paise());
    }

    @Test
    void headroomFloorsFractionalPaise() {
        // 40% of 3 paise = 1.2 -> allowed 1; with 0 EMI headroom is 1, never 2.
        assertEquals(1L, DebtTrapGuard.evaluate(Money.ofPaise(3L), Money.ZERO, CFG).headroom().paise());
    }

    @Test
    void positiveEmiWithZeroIncomeBlocks() {
        DebtTrapGuard.Result r = DebtTrapGuard.evaluate(Money.ZERO, Money.ofPaise(1L), CFG);
        assertEquals(Status.BLOCK, r.status());
        assertTrue(r.ratio().isEmpty());
        assertEquals(0L, r.headroom().paise());
    }

    @Test
    void positiveEmiWithNegativeIncomeBlocks() {
        DebtTrapGuard.Result r = DebtTrapGuard.evaluate(Money.ofPaise(-5L), Money.ofPaise(1L), CFG);
        assertEquals(Status.BLOCK, r.status());
        assertTrue(r.ratio().isEmpty());
    }

    @Test
    void zeroIncomeZeroEmiIsOkWithNoRatioAndNoHeadroom() {
        DebtTrapGuard.Result r = DebtTrapGuard.evaluate(Money.ZERO, Money.ZERO, CFG);
        assertEquals(Status.OK, r.status());
        assertTrue(r.ratio().isEmpty());
        assertEquals(0L, r.headroom().paise());
    }

    @Test
    void negativeEmiRejected() {
        assertThrows(IllegalArgumentException.class, () -> at(-1L));
    }

    @Test
    void thresholdsComeFromConfig() {
        RuleConfig strict = new RuleConfig(new BigDecimal("0.30"), new BigDecimal("0.35"), CFG.inflation(),
                CFG.equityReturn(), CFG.debtReturnLow(), CFG.debtReturnHigh(), CFG.fiMultiplier(),
                CFG.minHabitSipPaise(), CFG.budgetAlertWarn(), CFG.budgetAlertLimit(),
                CFG.equityMinHorizonYears());
        assertEquals(Status.WARN, DebtTrapGuard.evaluate(INCOME, Money.ofPaise(3_000_001L), strict).status());
        assertEquals(Status.BLOCK, DebtTrapGuard.evaluate(INCOME, Money.ofPaise(3_500_001L), strict).status());
    }

    @Test
    void addingEmiMovesWarnToBlock() {
        // 49.87% -> WARN; a further 1.57% of income -> above 50% -> BLOCK.
        Money income = Money.ofPaise(12_700_000L);
        assertEquals(Status.WARN, DebtTrapGuard.evaluate(income, Money.ofPaise(6_333_200L), CFG).status());
        assertEquals(Status.BLOCK, DebtTrapGuard.evaluate(income, Money.ofPaise(6_533_200L), CFG).status());
    }
}
