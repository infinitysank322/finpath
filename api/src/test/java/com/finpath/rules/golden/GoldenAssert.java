package com.finpath.rules.golden;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/** The ONLY tolerance mechanism for golden assertions. Failure messages never show values. */
public final class GoldenAssert {

    private static final BigDecimal ONE_PERCENT = new BigDecimal("0.01");
    private static final MathContext MC = new MathContext(20, RoundingMode.HALF_EVEN);

    private GoldenAssert() {
    }

    public static void assertWithinOnePercent(long expected, long actual) {
        assertWithinOnePercent(BigDecimal.valueOf(expected), BigDecimal.valueOf(actual));
    }

    public static void assertWithinOnePercent(BigDecimal expected, BigDecimal actual) {
        assertWithinOnePercent("value", expected, actual);
    }

    /** {@code label} names the figure (a key, never a value). Inclusive at exactly 1%. */
    public static void assertWithinOnePercent(String label, BigDecimal expected, BigDecimal actual) {
        BigDecimal diff = actual.subtract(expected).abs();
        BigDecimal allowed = expected.abs().multiply(ONE_PERCENT, MC);
        if (diff.compareTo(allowed) > 0) {
            String deviation;
            if (expected.signum() == 0) {
                deviation = "expected is zero, actual is not";
            } else {
                deviation = "deviation " + diff.divide(expected.abs(), MC).movePointRight(2)
                        .setScale(2, RoundingMode.HALF_EVEN).toPlainString() + "% exceeds 1%";
            }
            throw new AssertionError("Golden figure '" + label + "' outside 1% tolerance: " + deviation
                    + " (values withheld)");
        }
    }
}
