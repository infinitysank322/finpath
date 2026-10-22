package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Neutral synthetic figures only. Start day 25; reference 2030-01-10 is in the cycle of 2029-12-25. */
class EmiRollOffTest {

    private static final int DAY = 25;
    private static final LocalDate REF = LocalDate.of(2030, 1, 10);

    private static Money rs(long r) {
        return Money.ofPaise(r * 100);
    }

    @Test
    void stepsCarryCycleStartAndRunningTotals() {
        // A: 3 EMIs left -> cycles 0,1,2 paid, freed in cycle 3 (2030-03-25).
        // B: 1 EMI left -> freed in cycle 1 (2030-01-25). C: 6 left -> cycle 6 (2030-06-25).
        List<EmiRollOff.Step> s = EmiRollOff.timeline(List.of(
                new EmiRollOff.Debt("A", rs(1_000), 3),
                new EmiRollOff.Debt("B", rs(400), 1),
                new EmiRollOff.Debt("C", rs(250), 6)), REF, DAY);
        assertEquals(3, s.size());
        assertEquals(LocalDate.of(2030, 1, 25), s.get(0).cycleId());
        assertEquals(1, s.get(0).cycleIndex());
        assertEquals(rs(400), s.get(0).freed());
        assertEquals(LocalDate.of(2030, 3, 25), s.get(1).cycleId());
        assertEquals(rs(1_000), s.get(1).freed());
        assertEquals(rs(1_400), s.get(1).cumulativeFreed());
        assertEquals(LocalDate.of(2030, 6, 25), s.get(2).cycleId());
        assertEquals(rs(1_650), s.get(2).cumulativeFreed());
        assertEquals(rs(1_650), s.get(2).cumulativeRedirected());
    }

    @Test
    void sameEndCycleMergesIntoOneStep() {
        List<EmiRollOff.Step> s = EmiRollOff.timeline(List.of(
                new EmiRollOff.Debt("A", rs(300), 2), new EmiRollOff.Debt("B", rs(200), 2)), REF, DAY);
        assertEquals(1, s.size());
        assertEquals(rs(500), s.get(0).freed());
        assertEquals(List.of("A", "B"), s.get(0).debtIds());
    }

    @Test
    void closedLoansAndEmptyInputGiveNoSteps() {
        assertTrue(EmiRollOff.timeline(List.of(new EmiRollOff.Debt("A", rs(300), 0)), REF, DAY).isEmpty());
        assertTrue(EmiRollOff.timeline(List.of(), REF, DAY).isEmpty());
    }

    @Test
    void defaultRedirectIsAllAndPartialRedirectScales() {
        List<EmiRollOff.Debt> d = List.of(new EmiRollOff.Debt("A", rs(1_001), 1));
        assertEquals(rs(1_001), EmiRollOff.timeline(d, REF, DAY).get(0).redirected());
        EmiRollOff.Step half = EmiRollOff.timeline(d, REF, DAY, new BigDecimal("0.5")).get(0);
        assertEquals(rs(1_001), half.freed());
        assertEquals(Money.ofPaise(50_050), half.redirected()); // 100,100 paise x 0.5
        assertEquals(Money.ZERO, EmiRollOff.timeline(d, REF, DAY, BigDecimal.ZERO).get(0).redirected());
    }

    @Test
    void finalInstalmentFreesNextCycleAndEndIndexFactoryMatches() {
        // Last EMI in the reference cycle (index 0) -> freed in cycle 1.
        EmiRollOff.Step a = EmiRollOff.timeline(List.of(EmiRollOff.Debt.endingInCycle("A", rs(10), 0)),
                REF, DAY).get(0);
        assertEquals(LocalDate.of(2030, 1, 25), a.cycleId());
        assertEquals(new EmiRollOff.Debt("A", rs(10), 1), EmiRollOff.Debt.endingInCycle("A", rs(10), 0));
    }

    @Test
    void cycleSpanningMonthEndAndYearEnd() {
        // Start day 31 clamps in short months: 2030-01-31 ref, 1 EMI -> freed cycle starts 2030-02-28.
        EmiRollOff.Step a = EmiRollOff.timeline(List.of(new EmiRollOff.Debt("A", rs(10), 1)),
                LocalDate.of(2030, 1, 31), 31).get(0);
        assertEquals(LocalDate.of(2030, 2, 28), a.cycleId());
        // 25-day start, ref 2030-12-24 is still in the 2030-11-25 cycle; 2 EMIs -> 2031-01-25.
        EmiRollOff.Step b = EmiRollOff.timeline(List.of(new EmiRollOff.Debt("A", rs(10), 2)),
                LocalDate.of(2030, 12, 24), DAY).get(0);
        assertEquals(LocalDate.of(2031, 1, 25), b.cycleId());
    }

    @Test
    void zeroEmiDebtProducesZeroStepAndInvalidInputsRejected() {
        assertEquals(Money.ZERO, EmiRollOff.timeline(List.of(new EmiRollOff.Debt("A", Money.ZERO, 2)),
                REF, DAY).get(0).freed());
        assertThrows(IllegalArgumentException.class, () -> new EmiRollOff.Debt("A", rs(1), -1));
        assertThrows(IllegalArgumentException.class, () -> new EmiRollOff.Debt("A", Money.ofPaise(-1), 1));
        assertThrows(IllegalArgumentException.class, () -> EmiRollOff.timeline(List.of(), REF, DAY, new BigDecimal("1.01")));
        assertThrows(IllegalArgumentException.class, () -> EmiRollOff.timeline(List.of(), REF, 0));
    }

    @Test
    void cycleIdIsCycleStartDate() {
        EmiRollOff.Step s = EmiRollOff.timeline(List.of(new EmiRollOff.Debt("A", rs(10), 1)),
                LocalDate.of(2030, 12, 30), DAY).get(0);
        assertEquals(LocalDate.of(2031, 1, 25), s.cycleId());
    }

    @Test
    void halfUpTieRoundsAwayFromZero() {
        EmiRollOff.Step s = EmiRollOff.timeline(List.of(new EmiRollOff.Debt("A", Money.ofPaise(1), 1)),
                REF, DAY, new BigDecimal("0.5")).get(0);
        assertEquals(Money.ofPaise(1), s.redirected());
    }

    @Test
    void duplicateDebtIdsRejected() {
        assertThrows(IllegalArgumentException.class, () -> EmiRollOff.timeline(List.of(
                new EmiRollOff.Debt("A", rs(1), 1), new EmiRollOff.Debt("A", rs(2), 3)), REF, DAY));
    }
}
