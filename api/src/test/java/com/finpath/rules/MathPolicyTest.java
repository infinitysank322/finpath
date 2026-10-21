package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.Test;

class MathPolicyTest {

    @Test
    void policyValues() {
        assertEquals(34, MathPolicy.CALC.getPrecision());
        assertEquals(10, MathPolicy.RATE_SCALE);
        assertEquals(2, MathPolicy.RUPEE_SCALE);
        assertEquals(RoundingMode.HALF_UP, MathPolicy.MONEY_ROUNDING);
    }

    @Test
    void calcContextDividesNonTerminatingWithoutThrowing() {
        BigDecimal third = BigDecimal.ONE.divide(new BigDecimal("3"), MathPolicy.CALC);
        assertEquals(34, third.precision());
    }
}
