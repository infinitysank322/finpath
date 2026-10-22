package com.finpath.rules;

import java.math.MathContext;
import java.math.RoundingMode;

/** Single place that defines precision and rounding for all rule arithmetic. */
public final class MathPolicy {

    /** Precision for intermediate calculations (34 digits, half-even). */
    public static final MathContext CALC = MathContext.DECIMAL128;

    /** Scale for rates and ratios held as fractions. */
    public static final int RATE_SCALE = 10;

    /** Scale for rupee amounts (paise precision). */
    public static final int RUPEE_SCALE = 2;

    /** Rounding used when a value is finalised to paise or to a displayed ratio. */
    public static final RoundingMode MONEY_ROUNDING = RoundingMode.HALF_UP;

    private MathPolicy() {
    }
}
