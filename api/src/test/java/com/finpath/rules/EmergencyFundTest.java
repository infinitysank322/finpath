package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EmergencyFundTest {

    private static final Money OUTFLOW = Money.ofPaise(8_000_000L);

    @Test
    void targetIsMonthsTimesOutflow() {
        assertEquals(8_000_000L, EmergencyFund.target(OUTFLOW, 1).paise());
        assertEquals(24_000_000L, EmergencyFund.target(OUTFLOW, 3).paise());
        assertEquals(0L, EmergencyFund.target(Money.ZERO, 3).paise());
    }

    @Test
    void monthsCoveredRoundsDown() {
        assertEquals(new BigDecimal("0.16"), EmergencyFund.monthsCovered(Money.ofPaise(1_300_000L), OUTFLOW));
        assertEquals(new BigDecimal("1.00"), EmergencyFund.monthsCovered(OUTFLOW, OUTFLOW));
        assertEquals(new BigDecimal("0.00"), EmergencyFund.monthsCovered(Money.ZERO, OUTFLOW));
    }

    @Test
    void zeroOutflowHasNoCover() {
        assertNull(EmergencyFund.monthsCovered(OUTFLOW, Money.ZERO));
    }

    @Test
    void shortfallIsGapToTargetAndNeverNegative() {
        Money target = EmergencyFund.target(OUTFLOW, 3);
        assertEquals(24_000_000L - 1_300_000L, EmergencyFund.shortfall(Money.ofPaise(1_300_000L), target).paise());
        assertEquals(0L, EmergencyFund.shortfall(target, target).paise());
        assertEquals(0L, EmergencyFund.shortfall(Money.ofPaise(25_000_000L), target).paise());
        assertThrows(IllegalArgumentException.class, () -> EmergencyFund.shortfall(Money.ofPaise(-1), target));
    }

    @Test
    void monthlyOutflowIsActualFlooredAtCommitments() {
        assertEquals(9L, EmergencyFund.monthlyOutflow(Money.ofPaise(9), Money.ofPaise(5)).paise());
        assertEquals(5L, EmergencyFund.monthlyOutflow(Money.ofPaise(2), Money.ofPaise(5)).paise());
        assertEquals(0L, EmergencyFund.monthlyOutflow(Money.ZERO, Money.ZERO).paise());
        assertThrows(IllegalArgumentException.class,
                () -> EmergencyFund.monthlyOutflow(Money.ofPaise(-1), Money.ZERO));
    }

    @Test
    void rejectsBadInput() {
        assertThrows(IllegalArgumentException.class, () -> EmergencyFund.target(OUTFLOW, 0));
        assertThrows(IllegalArgumentException.class, () -> EmergencyFund.target(Money.ofPaise(-1), 1));
        assertThrows(IllegalArgumentException.class, () -> EmergencyFund.monthsCovered(Money.ofPaise(-1), OUTFLOW));
        assertThrows(NullPointerException.class, () -> EmergencyFund.target(null, 1));
    }
}
