package com.finpath.rules;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Immutable thresholds and assumptions for the rules engine. Rates are fractions
 * (0.06 = 6%). Money is in paise. Every rule reads its numbers from here; no call
 * site may carry a literal for these values.
 */
public record RuleConfig(
        BigDecimal emiToIncomeWarn,
        BigDecimal emiToIncomeBlock,
        BigDecimal inflation,
        BigDecimal equityReturn,
        BigDecimal debtReturnLow,
        BigDecimal debtReturnHigh,
        int fiMultiplier,
        long minHabitSipPaise,
        BigDecimal budgetAlertWarn,
        BigDecimal budgetAlertLimit,
        int equityMinHorizonYears) {

    public RuleConfig {
        Objects.requireNonNull(emiToIncomeWarn, "emiToIncomeWarn");
        Objects.requireNonNull(emiToIncomeBlock, "emiToIncomeBlock");
        Objects.requireNonNull(inflation, "inflation");
        Objects.requireNonNull(equityReturn, "equityReturn");
        Objects.requireNonNull(debtReturnLow, "debtReturnLow");
        Objects.requireNonNull(debtReturnHigh, "debtReturnHigh");
        Objects.requireNonNull(budgetAlertWarn, "budgetAlertWarn");
        Objects.requireNonNull(budgetAlertLimit, "budgetAlertLimit");
        if (emiToIncomeWarn.compareTo(emiToIncomeBlock) > 0) {
            throw new IllegalArgumentException("EMI warn threshold must not exceed block threshold");
        }
        if (debtReturnLow.compareTo(debtReturnHigh) > 0) {
            throw new IllegalArgumentException("debt return low must not exceed high");
        }
        if (budgetAlertWarn.compareTo(budgetAlertLimit) > 0) {
            throw new IllegalArgumentException("budget warn alert must not exceed limit alert");
        }
        if (fiMultiplier <= 0) {
            throw new IllegalArgumentException("fiMultiplier must be positive");
        }
        if (minHabitSipPaise < 0) {
            throw new IllegalArgumentException("minHabitSipPaise must not be negative");
        }
        if (equityMinHorizonYears < 0) {
            throw new IllegalArgumentException("equityMinHorizonYears must not be negative");
        }
    }

    /** Product defaults from the BRD. */
    public static RuleConfig defaults() {
        return new RuleConfig(
                new BigDecimal("0.40"),
                new BigDecimal("0.50"),
                new BigDecimal("0.06"),
                new BigDecimal("0.12"),
                new BigDecimal("0.07"),
                new BigDecimal("0.0825"),
                30,
                500_000L, // Rs 5,000 expressed in paise
                new BigDecimal("0.80"),
                new BigDecimal("1.00"),
                3);
    }
}
