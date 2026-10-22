package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class StepUpSipTest {

    private static final BigDecimal RET = new BigDecimal("0.12");
    private static final BigDecimal STEP = new BigDecimal("0.10");
    private static final Money SIP = Money.ofPaise(1_000_000L);
    private static final long ONE_RUPEE = 100L;

    /** Reference: month-by-month simulation. */
    private static BigDecimal loop(Money first, BigDecimal ret, BigDecimal step, int months) {
        BigDecimal i = ret.divide(BigDecimal.valueOf(12), MathPolicy.CALC);
        BigDecimal balance = BigDecimal.ZERO;
        BigDecimal monthly = new BigDecimal(first.paise());
        for (int m = 1; m <= months; m++) {
            balance = balance.multiply(BigDecimal.ONE.add(i), MathPolicy.CALC).add(monthly);
            if (m % 12 == 0) {
                monthly = monthly.multiply(BigDecimal.ONE.add(step), MathPolicy.CALC);
            }
        }
        return balance;
    }

    private static void assertMatchesLoop(Money first, BigDecimal ret, BigDecimal step, int months) {
        long closed = StepUpSip.futureValue(first, ret, step, months).paise();
        long looped = loop(first, ret, step, months).setScale(0, RoundingMode.HALF_UP).longValueExact();
        assertTrue(Math.abs(closed - looped) <= ONE_RUPEE, closed + " vs " + looped);
    }

    @Test
    void closedFormMatches156MonthLoop() {
        assertMatchesLoop(SIP, RET, STEP, 156);
    }

    @Test
    void matchesLoopForPartialYearsAndShortHorizons() {
        for (int months : new int[] {1, 5, 12, 13, 25, 100, 301}) {
            assertMatchesLoop(SIP, RET, STEP, months);
        }
    }

    @Test
    void zeroStepUpIsPlainSip() {
        assertMatchesLoop(SIP, RET, BigDecimal.ZERO, 156);
    }

    @Test
    void zeroReturnIsSumOfContributions() {
        // 12 months at 10,000 then 12 at 11,000 (each in paise)
        long expected = 12L * 1_000_000L + 12L * 1_100_000L;
        assertEquals(expected, StepUpSip.futureValue(SIP, BigDecimal.ZERO, STEP, 24).paise());
    }

    @Test
    void stepUpEqualToYearGrowthUsesLimitCase() {
        // growth over a year (1+i)^12 equals 1+stepUp: pick i small so step is derived exactly
        BigDecimal i = RET.divide(BigDecimal.valueOf(12), MathPolicy.CALC);
        BigDecimal step = BigDecimal.ONE.add(i).pow(12, MathPolicy.CALC).subtract(BigDecimal.ONE);
        assertMatchesLoop(SIP, RET, step, 60);
    }

    @Test
    void zeroMonthsIsZero() {
        assertEquals(Money.ZERO, StepUpSip.futureValue(SIP, RET, STEP, 0));
    }

    @Test
    void requiredSipRoundTripsThroughFutureValue() {
        Money target = Money.ofPaise(500_000_000L);
        Money first = StepUpSip.requiredFirstMonthly(target, RET, STEP, 156);
        Money fv = StepUpSip.futureValue(first, RET, STEP, 156);
        assertTrue(fv.paise() >= target.paise(), "reaches target");
        assertTrue(fv.paise() - target.paise() <= 1_000L, "does not overshoot materially");
        Money oneLess = StepUpSip.futureValue(Money.ofPaise(first.paise() - 1), RET, STEP, 156);
        assertTrue(oneLess.paise() <= target.paise(), "rounded up, not beyond");
    }

    @Test
    void zeroTargetNeedsNothing() {
        assertEquals(Money.ZERO, StepUpSip.requiredFirstMonthly(Money.ZERO, RET, STEP, 60));
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> StepUpSip.futureValue(SIP, RET, STEP, -1));
        assertThrows(IllegalArgumentException.class,
                () -> StepUpSip.futureValue(SIP, RET.negate(), STEP, 12));
        assertThrows(IllegalArgumentException.class,
                () -> StepUpSip.requiredFirstMonthly(SIP, RET, STEP, 0));
        assertThrows(IllegalArgumentException.class,
                () -> StepUpSip.requiredFirstMonthly(Money.ofPaise(-1), RET, STEP, 12));
    }

    @Test
    void feedsFiYearsSolve() {
        Money expense = Money.ofPaise(5_000_000L);
        var years = FiCorpus.fiYears(expense, Money.ZERO, 40,
                y -> StepUpSip.futureValue(Money.ofPaise(20_000_000L), RET, STEP, y * 12), RuleConfig.defaults());
        assertTrue(years.isPresent());
    }

    @Test
    void zeroReturnWithPartialYear() {
        assertMatchesLoop(SIP, BigDecimal.ZERO, STEP, 30);
    }
}
