package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RuleConfigTest {

    private final RuleConfig c = RuleConfig.defaults();

    @Test
    void defaultsMatchBrd() {
        assertEquals(0, c.emiToIncomeWarn().compareTo(new BigDecimal("0.40")));
        assertEquals(0, c.emiToIncomeBlock().compareTo(new BigDecimal("0.50")));
        assertEquals(0, c.inflation().compareTo(new BigDecimal("0.06")));
        assertEquals(0, c.equityReturn().compareTo(new BigDecimal("0.12")));
        assertEquals(0, c.debtReturnLow().compareTo(new BigDecimal("0.07")));
        assertEquals(0, c.debtReturnHigh().compareTo(new BigDecimal("0.0825")));
        assertEquals(30, c.fiMultiplier());
        assertEquals(500_000L, c.minHabitSipPaise());
        assertEquals(0, c.budgetAlertWarn().compareTo(new BigDecimal("0.80")));
        assertEquals(0, c.budgetAlertLimit().compareTo(BigDecimal.ONE));
        assertEquals(3, c.equityMinHorizonYears());
    }

    @Test
    void minHabitSipIsFiveThousandRupees() {
        assertEquals(new BigDecimal("5000.00"), Money.ofPaise(c.minHabitSipPaise()).toRupees());
    }

    @Test
    void rejectsInvertedEmiThresholds() {
        assertThrows(IllegalArgumentException.class, () -> new RuleConfig(
                c.emiToIncomeBlock(), c.emiToIncomeWarn(), c.inflation(), c.equityReturn(),
                c.debtReturnLow(), c.debtReturnHigh(), 30, 1L, c.budgetAlertWarn(),
                c.budgetAlertLimit(), 3));
    }

    @Test
    void rejectsInvertedDebtRange() {
        assertThrows(IllegalArgumentException.class, () -> new RuleConfig(
                c.emiToIncomeWarn(), c.emiToIncomeBlock(), c.inflation(), c.equityReturn(),
                c.debtReturnHigh(), c.debtReturnLow(), 30, 1L, c.budgetAlertWarn(),
                c.budgetAlertLimit(), 3));
    }

    @Test
    void rejectsInvertedBudgetAlerts() {
        assertThrows(IllegalArgumentException.class, () -> new RuleConfig(
                c.emiToIncomeWarn(), c.emiToIncomeBlock(), c.inflation(), c.equityReturn(),
                c.debtReturnLow(), c.debtReturnHigh(), 30, 1L, c.budgetAlertLimit(),
                c.budgetAlertWarn(), 3));
    }

    @Test
    void rejectsNonPositiveMultiplierAndNegativeSip() {
        assertThrows(IllegalArgumentException.class, () -> new RuleConfig(
                c.emiToIncomeWarn(), c.emiToIncomeBlock(), c.inflation(), c.equityReturn(),
                c.debtReturnLow(), c.debtReturnHigh(), 0, 1L, c.budgetAlertWarn(),
                c.budgetAlertLimit(), 3));
        assertThrows(IllegalArgumentException.class, () -> new RuleConfig(
                c.emiToIncomeWarn(), c.emiToIncomeBlock(), c.inflation(), c.equityReturn(),
                c.debtReturnLow(), c.debtReturnHigh(), 30, -1L, c.budgetAlertWarn(),
                c.budgetAlertLimit(), 3));
    }

    @Test
    void rejectsNullRate() {
        assertThrows(NullPointerException.class, () -> new RuleConfig(
                null, c.emiToIncomeBlock(), c.inflation(), c.equityReturn(),
                c.debtReturnLow(), c.debtReturnHigh(), 30, 1L, c.budgetAlertWarn(),
                c.budgetAlertLimit(), 3));
    }

    @Test
    void equalWarnAndBlockAllowed() {
        assertDoesNotThrow(() -> new RuleConfig(
                c.emiToIncomeWarn(), c.emiToIncomeWarn(), c.inflation(), c.equityReturn(),
                c.debtReturnLow(), c.debtReturnHigh(), 30, 0L, c.budgetAlertWarn(),
                c.budgetAlertLimit(), 3));
    }
}
