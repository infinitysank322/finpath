package com.finpath.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.finpath.rules.PayoffPlanner.DebtEntry;
import com.finpath.rules.PayoffPlanner.Plan;
import com.finpath.rules.PayoffPlanner.Strategy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class PayoffPlannerTest {

    private static Money rs(long rupees) {
        return Money.ofPaise(rupees * 100);
    }

    private static BigDecimal r(String s) {
        return new BigDecimal(s);
    }

    private static DebtEntry debt(String id, String rate, long balance, long pay) {
        return new DebtEntry(id, r(rate), rs(balance), rs(pay));
    }

    private static List<String> ids(List<DebtEntry> l) {
        return l.stream().map(DebtEntry::id).toList();
    }

    @Test
    void avalancheOrdersByRateDescSnowballByBalanceAsc() {
        List<DebtEntry> debts = List.of(
                debt("big-low", "0.0900", 900_000, 10_000),
                debt("small-mid", "0.1100", 30_000, 3_000),
                debt("mid-high", "0.1900", 200_000, 12_000));
        assertEquals(List.of("mid-high", "small-mid", "big-low"), ids(PayoffPlanner.order(debts, Strategy.AVALANCHE)));
        assertEquals(List.of("small-mid", "mid-high", "big-low"), ids(PayoffPlanner.order(debts, Strategy.SNOWBALL)));
    }

    @Test
    void rateTieBreaksByLargerBalanceThenId() {
        List<DebtEntry> debts = List.of(
                debt("b", "0.1000", 50_000, 2_000),
                debt("a", "0.1000", 50_000, 2_000),
                debt("c", "0.1000", 80_000, 2_000));
        assertEquals(List.of("c", "a", "b"), ids(PayoffPlanner.order(debts, Strategy.AVALANCHE)));
    }

    @Test
    void balanceTieBreaksByHigherRateThenId() {
        List<DebtEntry> debts = List.of(
                debt("z", "0.1000", 40_000, 2_000),
                debt("y", "0.1000", 40_000, 2_000),
                debt("x", "0.2000", 40_000, 2_000));
        assertEquals(List.of("x", "y", "z"), ids(PayoffPlanner.order(debts, Strategy.SNOWBALL)));
    }

    @Test
    void orderIsIndependentOfInputOrder() {
        DebtEntry a = debt("a", "0.1000", 50_000, 2_000);
        DebtEntry b = debt("b", "0.1000", 50_000, 2_000);
        assertEquals(PayoffPlanner.order(List.of(a, b), Strategy.AVALANCHE),
                PayoffPlanner.order(List.of(b, a), Strategy.AVALANCHE));
    }

    @Test
    void closedLoansAreExcluded() {
        List<DebtEntry> debts = List.of(
                debt("closed", "0.2500", 0, 9_000),
                debt("open", "0.1000", 40_000, 5_000));
        assertEquals(List.of("open"), ids(PayoffPlanner.order(debts, Strategy.AVALANCHE)));
        Plan p = PayoffPlanner.plan(debts, Strategy.AVALANCHE);
        assertEquals(List.of("open"), p.order());
        assertFalse(p.payoffCycle().containsKey("closed"));
    }

    /*
     * Hand-computed, amounts in rupees. A: 10,000 at 12% (1% a month), pays 6,000.
     * B: 9,000 at 0%, pays 3,000. Budget 9,000 a cycle. Avalanche targets A.
     * Cycle 0: A interest 100 -> 10,100, pay 6,000 -> 4,100. B pay 3,000 -> 6,000. Extra 0.
     * Cycle 1: A interest 41 -> 4,141, pay 4,141 -> 0 (closes, index 1). B pay 3,000 -> 3,000;
     *          leftover 9,000 - 4,141 - 3,000 = 1,859 rolls to B -> 1,141.
     * Cycle 2: B pay 1,141 -> 0 (closes, index 2).
     * Total interest 100 + 41 = 141. Total paid 19,000 + 141 = 19,141. Cycles 3.
     */
    @Test
    void twoDebtCascadeMatchesHandComputation() {
        Plan p = PayoffPlanner.plan(List.of(debt("b", "0", 9_000, 3_000), debt("a", "0.1200", 10_000, 6_000)),
                Strategy.AVALANCHE);
        assertEquals(List.of("a", "b"), p.order());
        assertEquals(1, p.payoffCycle().get("a"));
        assertEquals(2, p.payoffCycle().get("b"));
        assertEquals(rs(141), p.totalInterest());
        assertEquals(rs(19_141), p.totalPaid());
        assertEquals(3, p.cycles());
    }

    @Test
    void closedLoanPaymentDoesNotInflateBudget() {
        DebtEntry open = debt("open", "0.1000", 40_000, 5_000);
        Plan alone = PayoffPlanner.plan(List.of(open), Strategy.AVALANCHE);
        Plan withClosed = PayoffPlanner.plan(
                List.of(debt("closed", "0.2500", 0, 9_000), open), Strategy.AVALANCHE);
        assertEquals(alone, withClosed);
    }

    @Test
    void nothingOwedGivesEmptyPlan() {
        Plan p = PayoffPlanner.plan(List.of(), Strategy.SNOWBALL);
        assertEquals(0, p.cycles());
        assertEquals(Money.ZERO, p.totalInterest());
        assertTrue(p.order().isEmpty());
    }

    @Test
    void nullStrategyAndDuplicateIdsAreRejected() {
        List<DebtEntry> debts = List.of(debt("a", "0.1", 1_000, 100));
        assertThrows(NullPointerException.class, () -> PayoffPlanner.plan(debts, null));
        assertThrows(IllegalArgumentException.class, () -> PayoffPlanner.order(
                List.of(debt("a", "0.1", 1_000, 100), debt("a", "0.2", 2_000, 100)), Strategy.AVALANCHE));
    }

    @Test
    void invalidEntriesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new DebtEntry("a", r("-0.01"), rs(1), rs(1)));
        assertThrows(IllegalArgumentException.class, () -> new DebtEntry("a", r("0.1"), rs(-1), rs(1)));
        assertThrows(IllegalArgumentException.class, () -> new DebtEntry("a", r("0.1"), rs(1), rs(-1)));
    }

    @Test
    void singleDebtMatchesAmortisingSchedule() {
        Money principal = rs(120_000);
        BigDecimal rate = r("0.1200");
        Money emi = AmortisingLoan.emi(principal, rate, 24);
        AmortisingLoan.Schedule s = AmortisingLoan.schedule(principal, rate, emi, 24);
        Plan p = PayoffPlanner.plan(List.of(new DebtEntry("a", rate, principal, emi)), Strategy.AVALANCHE);
        assertEquals(s.totalInterest(), p.totalInterest());
        assertEquals(s.totalPaid(), p.totalPaid());
        assertEquals(s.size() - 1, p.payoffCycle().get("a"));
        assertEquals(s.size(), p.cycles());
    }

    @Test
    void cascadeConservesMoneyAndClosesAtExactlyZero() {
        List<DebtEntry> debts = List.of(
                debt("high", "0.1800", 100_000, 10_000),
                debt("low", "0.1000", 50_000, 5_000),
                debt("tiny", "0.0900", 12_000, 4_000));
        for (Strategy st : Strategy.values()) {
            Plan p = PayoffPlanner.plan(debts, st);
            Money principal = Money.ZERO;
            for (DebtEntry d : debts) {
                principal = principal.plus(d.outstanding());
            }
            assertEquals(principal.plus(p.totalInterest()), p.totalPaid(), st.name());
            assertEquals(3, p.payoffCycle().size(), st.name());
            int last = p.payoffCycle().values().stream().mapToInt(Integer::intValue).max().orElseThrow();
            assertEquals(last + 1, p.cycles(), st.name());
        }
    }

    @Test
    void freedPaymentRollsToTargetAndBeatsIndependentSchedules() {
        DebtEntry tiny = debt("tiny", "0.0900", 12_000, 4_000);
        DebtEntry high = debt("high", "0.1800", 100_000, 10_000);
        Plan p = PayoffPlanner.plan(List.of(tiny, high), Strategy.AVALANCHE);
        AmortisingLoan.Schedule sTiny = AmortisingLoan.schedule(
                tiny.outstanding(), tiny.annualRate(), tiny.monthlyPayment(), PayoffPlanner.MAX_CYCLES);
        AmortisingLoan.Schedule sHigh = AmortisingLoan.schedule(
                high.outstanding(), high.annualRate(), high.monthlyPayment(), PayoffPlanner.MAX_CYCLES);
        // Without cascade the high-rate loan ends when its own schedule does; with it, earlier.
        assertTrue(p.payoffCycle().get("high") < sHigh.size() - 1);
        // The small loan is paid its normal instalment while it is open, so it ends on schedule.
        assertTrue(p.payoffCycle().get("tiny") <= sTiny.size() - 1);
        assertTrue(p.totalInterest().paise() < sTiny.totalInterest().plus(sHigh.totalInterest()).paise());
    }

    @Test
    void avalancheNeverPaysMoreInterestThanSnowballHere() {
        List<DebtEntry> debts = List.of(
                debt("big-high", "0.2000", 300_000, 15_000),
                debt("small-low", "0.0800", 20_000, 3_000));
        Plan av = PayoffPlanner.plan(debts, Strategy.AVALANCHE);
        Plan sn = PayoffPlanner.plan(debts, Strategy.SNOWBALL);
        assertTrue(av.totalInterest().paise() <= sn.totalInterest().paise());
        assertEquals(List.of("big-high", "small-low"), av.order());
        assertEquals(List.of("small-low", "big-high"), sn.order());
    }

    @Test
    void zeroRateDebtSettlesExactly() {
        Plan p = PayoffPlanner.plan(List.of(debt("free", "0", 10_000, 3_000)), Strategy.SNOWBALL);
        assertEquals(Money.ZERO, p.totalInterest());
        assertEquals(rs(10_000), p.totalPaid());
        assertEquals(3, p.payoffCycle().get("free"));
    }

    @Test
    void paymentBelowInterestFailsLoudly() {
        assertThrows(IllegalStateException.class, () -> PayoffPlanner.plan(
                List.of(debt("stuck", "0.2400", 100_000, 100)), Strategy.AVALANCHE));
    }

    @Test
    void interestOnlyEntryClearsOnlyThroughCascade() {
        // Interest-only entry: payment equals monthly interest, so it only ends via cascade.
        DebtEntry bullet = new DebtEntry("bullet", r("0.1200"), rs(100_000), rs(1_000));
        DebtEntry emi = debt("emi", "0.0800", 20_000, 5_000);
        Plan p = PayoffPlanner.plan(List.of(bullet, emi), Strategy.AVALANCHE);
        assertEquals(List.of("bullet", "emi"), p.order());
        assertEquals(rs(120_000).plus(p.totalInterest()), p.totalPaid());
        assertTrue(p.payoffCycle().get("emi") < p.payoffCycle().get("bullet"));
    }
}
