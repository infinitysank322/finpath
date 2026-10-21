package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class EmergencyFundTest {

    @Test
    void targetsAreOneAndThreeMonthsOfOutflow() {
        EmergencyFund.Result r = EmergencyFund.evaluate(Money.ofPaise(8_716_500L), Money.ofPaise(1_500_000L));
        assertEquals(8_716_500L, r.oneMonth().paise());
        assertEquals(26_149_500L, r.threeMonth().paise());
    }

    @Test
    void monthsCoveredRoundsToTwoPlaces() {
        EmergencyFund.Result r = EmergencyFund.evaluate(Money.ofPaise(8_716_500L), Money.ofPaise(1_500_000L));
        assertEquals(new BigDecimal("0.17"), r.monthsCovered());
    }

    @Test
    void monthsCoveredRoundsHalfUp() {
        // 1 / 8 = 0.125 -> 0.13
        assertEquals(new BigDecimal("0.13"),
                EmergencyFund.evaluate(Money.ofPaise(800L), Money.ofPaise(100L)).monthsCovered());
    }

    @Test
    void fullyFundedCoversThreeMonths() {
        EmergencyFund.Result r = EmergencyFund.evaluate(Money.ofPaise(100_000L), Money.ofPaise(300_000L));
        assertEquals(new BigDecimal("3.00"), r.monthsCovered());
    }

    @Test
    void zeroOutflowAndZeroFund() {
        EmergencyFund.Result r = EmergencyFund.evaluate(Money.ZERO, Money.ofPaise(500L));
        assertEquals(0L, r.oneMonth().paise());
        assertEquals(0L, r.threeMonth().paise());
        assertEquals(0, r.monthsCovered().signum());
        assertEquals(0, EmergencyFund.evaluate(Money.ofPaise(100L), Money.ZERO).monthsCovered().signum());
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThrows(IllegalArgumentException.class, () -> EmergencyFund.evaluate(Money.ofPaise(-1L), Money.ZERO));
        assertThrows(IllegalArgumentException.class, () -> EmergencyFund.evaluate(Money.ZERO, Money.ofPaise(-1L)));
    }
}
