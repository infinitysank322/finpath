package com.finpath.rules.golden;

import static com.finpath.rules.golden.GoldenAssert.assertWithinOnePercent;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class GoldenAssertTest {

    @Test
    void exactMatchPasses() {
        assertDoesNotThrow(() -> assertWithinOnePercent(1_000_000L, 1_000_000L));
    }

    @Test
    void exactlyOnePercentIsInclusiveBothSides() {
        assertDoesNotThrow(() -> assertWithinOnePercent(1_000_000L, 1_010_000L));
        assertDoesNotThrow(() -> assertWithinOnePercent(1_000_000L, 990_000L));
    }

    @Test
    void justOutsideOnePercentFails() {
        assertThrows(AssertionError.class, () -> assertWithinOnePercent(1_000_000L, 1_010_001L));
        assertThrows(AssertionError.class, () -> assertWithinOnePercent(1_000_000L, 989_999L));
    }

    @Test
    void decimalOverload() {
        assertDoesNotThrow(() -> assertWithinOnePercent(new BigDecimal("15.33"), new BigDecimal("15.30")));
        assertThrows(AssertionError.class,
                () -> assertWithinOnePercent(new BigDecimal("15.33"), new BigDecimal("15.00")));
    }

    @Test
    void zeroExpectedOnlyAcceptsZero() {
        assertDoesNotThrow(() -> assertWithinOnePercent(0L, 0L));
        assertThrows(AssertionError.class, () -> assertWithinOnePercent(0L, 1L));
    }

    @Test
    void negativeExpectedUsesMagnitude() {
        assertDoesNotThrow(() -> assertWithinOnePercent(-1_000_000L, -1_005_000L));
        assertThrows(AssertionError.class, () -> assertWithinOnePercent(-1_000_000L, -1_020_000L));
    }

    @Test
    void failureMessageNeverContainsTheValues() {
        AssertionError e = assertThrows(AssertionError.class,
                () -> assertWithinOnePercent("home.principal_paise", new BigDecimal("987654321"),
                        new BigDecimal("123456789")));
        assertFalse(e.getMessage().contains("987654321"));
        assertFalse(e.getMessage().contains("123456789"));
        AssertionError z = assertThrows(AssertionError.class, () -> assertWithinOnePercent(0L, 424242L));
        assertFalse(z.getMessage().contains("424242"));
    }
}
