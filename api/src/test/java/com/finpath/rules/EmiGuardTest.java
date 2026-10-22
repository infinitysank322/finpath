package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EmiGuardTest {

    private static final RuleConfig CFG = RuleConfig.defaults();
    private static final Money INCOME = Money.ofPaise(10_000_000L);

    private static EmiGuard.Result run(long emiPaise) {
        return EmiGuard.evaluate(Money.ofPaise(emiPaise), INCOME, CFG);
    }

    @Test
    void exactlyWarnThresholdIsOk() {
        assertEquals(EmiGuard.Status.OK, run(4_000_000L).status());
    }

    @Test
    void justAboveWarnThresholdIsWarn() {
        assertEquals(EmiGuard.Status.WARN, run(4_001_000L).status());
    }

    @Test
    void exactlyBlockThresholdIsWarnAndAboveIsBlock() {
        assertEquals(EmiGuard.Status.WARN, run(5_000_000L).status());
        assertEquals(EmiGuard.Status.BLOCK, run(5_000_001L).status());
    }

    @Test
    void ratioIsExactFraction() {
        assertEquals(0, new BigDecimal("0.25").compareTo(run(2_500_000L).ratio()));
    }

    @Test
    void headroomIsRoomBeforeBlock() {
        assertEquals(1_000_000L, run(4_000_000L).headroom().paise());
        assertEquals(0L, run(5_000_000L).headroom().paise());
        assertEquals(0L, run(6_000_000L).headroom().paise());
    }

    @Test
    void headroomRoundsDown() {
        EmiGuard.Result r = EmiGuard.evaluate(Money.ZERO, Money.ofPaise(1_001L), CFG);
        assertEquals(500L, r.headroom().paise()); // 500.5 -> 500
    }

    @Test
    void zeroIncome() {
        EmiGuard.Result none = EmiGuard.evaluate(Money.ZERO, Money.ZERO, CFG);
        assertEquals(EmiGuard.Status.OK, none.status());
        assertNull(none.ratio());
        assertEquals(EmiGuard.Status.BLOCK, EmiGuard.evaluate(Money.ofPaise(1), Money.ZERO, CFG).status());
    }

    @Test
    void zeroEmiIsOkWithFullHeadroom() {
        EmiGuard.Result r = run(0);
        assertEquals(EmiGuard.Status.OK, r.status());
        assertEquals(5_000_000L, r.headroom().paise());
    }

    @Test
    void rejectsNegativesAndNulls() {
        assertThrows(IllegalArgumentException.class,
                () -> EmiGuard.evaluate(Money.ofPaise(-1), INCOME, CFG));
        assertThrows(IllegalArgumentException.class,
                () -> EmiGuard.evaluate(Money.ZERO, Money.ofPaise(-1), CFG));
        assertThrows(NullPointerException.class, () -> EmiGuard.evaluate(null, INCOME, CFG));
    }

    @Test
    void customThresholdsAreHonoured() {
        RuleConfig d = CFG;
        RuleConfig tight = new RuleConfig(new BigDecimal("0.20"), new BigDecimal("0.30"), d.inflation(),
                d.equityReturn(), d.debtReturnLow(), d.debtReturnHigh(), d.fiMultiplier(), d.minHabitSipPaise(),
                d.budgetAlertWarn(), d.budgetAlertLimit(), d.equityMinHorizonYears());
        assertEquals(EmiGuard.Status.WARN, EmiGuard.evaluate(Money.ofPaise(2_500_000L), INCOME, tight).status());
        assertEquals(EmiGuard.Status.BLOCK, EmiGuard.evaluate(Money.ofPaise(3_500_000L), INCOME, tight).status());
    }

    @Test
    void totalSumsInstalments() {
        assertEquals(0L, EmiGuard.total(java.util.List.of()).paise());
        assertEquals(3_500_000L, EmiGuard.total(java.util.List.of(Money.ofPaise(2_000_000L),
                Money.ofPaise(1_500_000L))).paise());
        assertThrows(NullPointerException.class, () -> EmiGuard.total(null));
    }
}
