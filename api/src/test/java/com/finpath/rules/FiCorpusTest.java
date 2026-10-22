package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class FiCorpusTest {

    private static final RuleConfig CFG = RuleConfig.defaults();
    private static final Money EXPENSE = Money.ofPaise(10_000_000L);
    private static final Money PASSIVE = Money.ofPaise(2_000_000L);

    @Test
    void zeroYearsLeavesAmountUnchanged() {
        assertEquals(EXPENSE, FiCorpus.inflate(EXPENSE, 0, CFG));
    }

    @Test
    void inflationCompoundsAnnually() {
        // 1.06^2 = 1.1236
        assertEquals(11_236_000L, FiCorpus.inflate(EXPENSE, 2, CFG).paise());
    }

    @Test
    void grossCorpusIsYearlyExpenseTimesMultiplier() {
        FiCorpus.Result r = FiCorpus.corpus(EXPENSE, Money.ZERO, 0, CFG);
        assertEquals(EXPENSE.paise() * 12L * CFG.fiMultiplier(), r.grossCorpus().paise());
        assertEquals(r.grossCorpus(), r.netCorpus());
    }

    @Test
    void passiveIncomeIsInflatedAndNetted() {
        FiCorpus.Result r = FiCorpus.corpus(EXPENSE, PASSIVE, 2, CFG);
        assertEquals(2_247_200L, r.inflatedMonthlyPassive().paise());
        assertEquals((11_236_000L - 2_247_200L) * 12L * CFG.fiMultiplier(), r.netCorpus().paise());
    }

    @Test
    void multiplierComesFromConfig() {
        RuleConfig c = new RuleConfig(CFG.emiToIncomeWarn(), CFG.emiToIncomeBlock(), CFG.inflation(),
                CFG.equityReturn(), CFG.debtReturnLow(), CFG.debtReturnHigh(), 25, CFG.minHabitSipPaise(),
                CFG.budgetAlertWarn(), CFG.budgetAlertLimit(), CFG.equityMinHorizonYears());
        assertEquals(EXPENSE.paise() * 12L * 25L, FiCorpus.corpus(EXPENSE, Money.ZERO, 0, c).grossCorpus().paise());
    }

    @Test
    void inflationComesFromConfig() {
        RuleConfig c = new RuleConfig(CFG.emiToIncomeWarn(), CFG.emiToIncomeBlock(), new BigDecimal("0.10"),
                CFG.equityReturn(), CFG.debtReturnLow(), CFG.debtReturnHigh(), CFG.fiMultiplier(),
                CFG.minHabitSipPaise(), CFG.budgetAlertWarn(), CFG.budgetAlertLimit(), CFG.equityMinHorizonYears());
        assertEquals(12_100_000L, FiCorpus.inflate(EXPENSE, 2, c).paise());
    }

    @Test
    void passiveAboveExpenseGivesZeroNetCorpus() {
        FiCorpus.Result r = FiCorpus.corpus(PASSIVE, EXPENSE, 5, CFG);
        assertEquals(0L, r.netCorpus().paise());
        assertTrue(r.grossCorpus().paise() > 0);
    }

    @Test
    void rejectsNegativeInputs() {
        assertThrows(IllegalArgumentException.class, () -> FiCorpus.inflate(Money.ofPaise(-1), 1, CFG));
        assertThrows(IllegalArgumentException.class, () -> FiCorpus.inflate(EXPENSE, -1, CFG));
    }

    @Test
    void fiYearsFindsFirstYearSavingsCoverCorpus() {
        // Linear projection reaching the year-10 corpus at year 10; the solve must land at or after year 10.
        long target = FiCorpus.corpus(EXPENSE, PASSIVE, 10, CFG).netCorpus().paise();
        var y = FiCorpus.fiYears(EXPENSE, PASSIVE, 40, years -> Money.ofPaise(target * years / 10), CFG);
        assertTrue(y.isPresent());
        assertTrue(y.getAsInt() >= 10);
        long needed = FiCorpus.corpus(EXPENSE, PASSIVE, y.getAsInt(), CFG).netCorpus().paise();
        assertTrue(target * y.getAsInt() / 10 >= needed);
        if (y.getAsInt() > 0) {
            int prev = y.getAsInt() - 1;
            assertTrue(target * prev / 10 < FiCorpus.corpus(EXPENSE, PASSIVE, prev, CFG).netCorpus().paise());
        }
    }

    @Test
    void fiYearsEmptyWhenNeverReached() {
        assertTrue(FiCorpus.fiYears(EXPENSE, PASSIVE, 5, years -> Money.ZERO, CFG).isEmpty());
    }

    @Test
    void fiYearsZeroWhenAlreadyCovered() {
        assertEquals(0, FiCorpus.fiYears(EXPENSE, PASSIVE, 5, years -> Money.ofPaise(Long.MAX_VALUE / 2), CFG)
                .getAsInt());
    }

    @Test
    void overflowThrowsInsteadOfWrapping() {
        assertThrows(ArithmeticException.class,
                () -> FiCorpus.corpus(Money.ofPaise(Long.MAX_VALUE / 100), Money.ZERO, 0, CFG));
    }

    @Test
    void negativePassiveAndNegativeMaxYearsRejected() {
        assertThrows(IllegalArgumentException.class, () -> FiCorpus.corpus(EXPENSE, Money.ofPaise(-1), 1, CFG));
        assertThrows(IllegalArgumentException.class,
                () -> FiCorpus.fiYears(EXPENSE, PASSIVE, -1, y -> Money.ZERO, CFG));
    }

    @Test
    void passiveAboveExpenseIsFiAtYearZero() {
        assertEquals(0, FiCorpus.fiYears(PASSIVE, EXPENSE, 5, y -> Money.ZERO, CFG).getAsInt());
    }
}
