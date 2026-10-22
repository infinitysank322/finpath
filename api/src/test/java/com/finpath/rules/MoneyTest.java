package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void paiseToRupeesIsExact() {
        assertEquals(new BigDecimal("1234.56"), Money.ofPaise(123_456L).toRupees());
        assertEquals(new BigDecimal("0.00"), Money.ZERO.toRupees());
        assertEquals(new BigDecimal("-0.05"), Money.ofPaise(-5L).toRupees());
    }

    @Test
    void rupeesToPaiseRoundsHalfUp() {
        assertEquals(123_456L, Money.ofRupees(new BigDecimal("1234.56")).paise());
        assertEquals(1L, Money.ofRupees(new BigDecimal("0.005")).paise());
        assertEquals(0L, Money.ofRupees(new BigDecimal("0.004")).paise());
        assertEquals(0L, Money.ofRupees(BigDecimal.ZERO).paise());
    }

    @Test
    void roundTripIsLossless() {
        long p = 4_000_400L;
        assertEquals(p, Money.ofRupees(Money.ofPaise(p).toRupees()).paise());
    }

    @Test
    void arithmetic() {
        assertEquals(300L, Money.ofPaise(100).plus(Money.ofPaise(200)).paise());
        assertEquals(-100L, Money.ofPaise(100).minus(Money.ofPaise(200)).paise());
        assertEquals(15L, Money.ofPaise(100).times(new BigDecimal("0.145")).paise());
        assertThrows(ArithmeticException.class,
                () -> Money.ofPaise(Long.MAX_VALUE).plus(Money.ofPaise(1)));
    }

    @Test
    void divideWithoutScaleThrows() {
        assertThrows(UnsupportedOperationException.class,
                () -> Money.ofPaise(100).divide(new BigDecimal("3")));
    }

    @Test
    void divideWithScaleAndRounding() {
        assertEquals(new BigDecimal("0.33"),
                Money.ofPaise(100).divide(new BigDecimal("3"), 2, RoundingMode.HALF_UP));
    }

    @Test
    void divideByZeroThrows() {
        assertThrows(ArithmeticException.class,
                () -> Money.ofPaise(100).divide(BigDecimal.ZERO, 2, RoundingMode.HALF_UP));
    }
}
