package com.finpath.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Debt payoff ordering and multi-loan simulation (FR-14). Pure and deterministic: no clock, no I/O.
 *
 * <p>Each simulated cycle accrues interest at rate/12 on every open debt (rounded to paise, as in
 * {@link AmortisingLoan}), pays each debt its own monthly payment (capped at what it owes), then
 * pours everything left of the combined budget onto the current target debt. The budget is the
 * total of all supplied payments, so a closed loan's payment rolls to the next target. For a
 * bullet loan the supplied payment is its monthly interest; any cascaded extra is a principal
 * prepayment. Priority order (avalanche or snowball) is fixed once from the starting balances and
 * is not re-sorted as balances change during the simulation. Cycle indices are 0-based offsets from the first simulated cycle.
 */
public final class PayoffPlanner {

    /** Safety cap so a payment that never covers interest fails loudly instead of looping. */
    static final int MAX_CYCLES = 1200;

    private PayoffPlanner() {
    }

    /** Ordering strategy. No default: callers must choose. */
    public enum Strategy {
        /** Highest annual rate first. */
        AVALANCHE,
        /** Smallest outstanding balance first. */
        SNOWBALL
    }

    /**
     * A debt to plan. {@code annualRate} is a fraction (use {@link BulletLoan#effectiveAnnualRate}
     * for bullet loans); {@code monthlyPayment} is the EMI, or the interest for a bullet loan.
     */
    public record DebtEntry(String id, BigDecimal annualRate, Money outstanding, Money monthlyPayment) {

        public DebtEntry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(annualRate, "annualRate");
            Objects.requireNonNull(outstanding, "outstanding");
            Objects.requireNonNull(monthlyPayment, "monthlyPayment");
            if (annualRate.signum() < 0) {
                throw new IllegalArgumentException("annualRate must not be negative");
            }
            if (outstanding.isNegative() || monthlyPayment.isNegative()) {
                throw new IllegalArgumentException("amounts must not be negative");
            }
        }

        boolean isOpen() {
            return outstanding.paise() > 0;
        }
    }

    /** Outcome of a plan. {@code payoffCycle} maps debt id to the 0-based cycle it closes in. */
    public record Plan(Strategy strategy, List<String> order, Map<String, Integer> payoffCycle,
            Money totalInterest, Money totalPaid, int cycles) {

        public Plan {
            order = Collections.unmodifiableList(new ArrayList<>(order));
            payoffCycle = Collections.unmodifiableMap(new LinkedHashMap<>(payoffCycle));
        }
    }

    /** Open debts only, in payoff priority order for the strategy. */
    public static List<DebtEntry> order(List<DebtEntry> debts, Strategy strategy) {
        Objects.requireNonNull(debts, "debts");
        Objects.requireNonNull(strategy, "strategy");
        Set<String> ids = new HashSet<>();
        List<DebtEntry> open = new ArrayList<>();
        for (DebtEntry d : debts) {
            Objects.requireNonNull(d, "debt");
            if (!ids.add(d.id())) {
                throw new IllegalArgumentException("duplicate debt id: " + d.id());
            }
            if (d.isOpen()) {
                open.add(d);
            }
        }
        Comparator<DebtEntry> byRateDesc = Comparator.comparing(DebtEntry::annualRate).reversed();
        Comparator<DebtEntry> byBalanceAsc = Comparator.comparingLong(d -> d.outstanding().paise());
        Comparator<DebtEntry> byBalanceDesc = byBalanceAsc.reversed();
        Comparator<DebtEntry> byId = Comparator.comparing(DebtEntry::id);
        Comparator<DebtEntry> cmp = switch (strategy) {
            case AVALANCHE -> byRateDesc.thenComparing(byBalanceDesc).thenComparing(byId);
            case SNOWBALL -> byBalanceAsc.thenComparing(byRateDesc).thenComparing(byId);
        };
        open.sort(cmp);
        return open;
    }

    /** Simulates the whole payoff. Empty plan (zero cycles) when nothing is owed. */
    public static Plan plan(List<DebtEntry> debts, Strategy strategy) {
        List<DebtEntry> ordered = order(debts, strategy);
        int n = ordered.size();
        long[] balance = new long[n];
        BigDecimal[] rate = new BigDecimal[n];
        Money budget = Money.ZERO;
        for (int i = 0; i < n; i++) {
            DebtEntry d = ordered.get(i);
            balance[i] = d.outstanding().paise();
            rate[i] = AmortisingLoan.monthlyRate(d.annualRate());
            budget = budget.plus(d.monthlyPayment());
        }
        Map<String, Integer> closedAt = new LinkedHashMap<>();
        List<String> order = new ArrayList<>();
        for (DebtEntry d : ordered) {
            order.add(d.id());
        }
        Money totalInterest = Money.ZERO;
        Money totalPaid = Money.ZERO;
        int open = n;
        int cycle = 0;
        while (open > 0) {
            if (cycle >= MAX_CYCLES) {
                throw new IllegalStateException("payments never clear the debts within " + MAX_CYCLES + " cycles");
            }
            for (int i = 0; i < n; i++) {
                if (balance[i] > 0) {
                    Money interest = new Money(balance[i]).times(rate[i]);
                    balance[i] += interest.paise();
                    totalInterest = totalInterest.plus(interest);
                }
            }
            long left = budget.paise();
            for (int i = 0; i < n; i++) {
                if (balance[i] > 0) {
                    long pay = Math.min(ordered.get(i).monthlyPayment().paise(), balance[i]);
                    balance[i] -= pay;
                    left -= pay;
                    totalPaid = totalPaid.plus(new Money(pay));
                }
            }
            for (int i = 0; i < n && left > 0; i++) {
                if (balance[i] > 0) {
                    long pay = Math.min(left, balance[i]);
                    balance[i] -= pay;
                    left -= pay;
                    totalPaid = totalPaid.plus(new Money(pay));
                }
            }
            for (int i = 0; i < n; i++) {
                if (balance[i] == 0 && !closedAt.containsKey(ordered.get(i).id())) {
                    closedAt.put(ordered.get(i).id(), cycle);
                    open--;
                }
            }
            cycle++;
        }
        return new Plan(strategy, order, closedAt, totalInterest, totalPaid, cycle);
    }
}
