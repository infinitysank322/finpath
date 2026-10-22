package com.finpath.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * EMI roll-off timeline. Pure and deterministic: no clock, no I/O; the starting cycle is derived
 * from a caller-supplied reference date and the user's cycle start day.
 *
 * <p>Model: the cycle containing {@code reference} is cycle index 0 and pays the next EMI, so a
 * debt with {@code n} EMIs remaining pays in cycles 0..n-1 and the freed amount first becomes
 * available in cycle {@code n} (the cycle after the final EMI). Debts with no EMIs remaining
 * (already closed) are excluded. Debts ending in the same cycle merge into one step. The
 * redirected amount is {@code freed x redirectPct}, rounded HALF_UP to paise; the cumulative
 * figures add the per-step exact amounts so they never drift.
 */
public final class EmiRollOff {

    private EmiRollOff() {
    }

    /**
     * @param id debt identifier
     * @param monthlyEmi the EMI paid each cycle
     * @param emisRemaining EMIs still to be paid, counted from the reference cycle; 0 = closed
     */
    public record Debt(String id, Money monthlyEmi, int emisRemaining) {
        public Debt {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(monthlyEmi, "monthlyEmi");
            if (monthlyEmi.isNegative()) {
                throw new IllegalArgumentException("monthlyEmi must not be negative");
            }
            if (emisRemaining < 0) {
                throw new IllegalArgumentException("emisRemaining must not be negative");
            }
        }

        /** Debt whose last EMI falls in the cycle {@code endCycleIndex} (0 = the reference cycle). */
        public static Debt endingInCycle(String id, Money monthlyEmi, int endCycleIndex) {
            return new Debt(id, monthlyEmi, endCycleIndex + 1);
        }
    }

    /**
     * @param cycle the cycle in which the EMI no longer falls due
     * @param cycleIndex cycles after the reference cycle
     * @param debtIds debts that ended just before this cycle, in input order
     * @param freed monthly amount freed by these debts alone
     * @param cumulativeFreed total monthly amount freed up to and including this step
     * @param redirected {@code freed x pct}
     * @param cumulativeRedirected total redirected up to and including this step
     */
    public record Step(CycleCalendar.Cycle cycle, int cycleIndex, List<String> debtIds, Money freed,
                       Money cumulativeFreed, Money redirected, Money cumulativeRedirected) {
        /** Cycle id: the cycle's start date. */
        public LocalDate cycleId() {
            return cycle.start();
        }
    }

    /** Timeline using {@link RuleConfig#DEFAULT_EMI_REDIRECT}. */
    public static List<Step> timeline(List<Debt> debts, LocalDate reference, int startDay) {
        return timeline(debts, reference, startDay, RuleConfig.DEFAULT_EMI_REDIRECT);
    }

    /**
     * @param redirectPct share of each freed EMI sent to goals, a fraction in 0..1
     * @return steps in cycle order; empty when every debt is closed
     */
    public static List<Step> timeline(List<Debt> debts, LocalDate reference, int startDay,
                                      BigDecimal redirectPct) {
        Objects.requireNonNull(debts, "debts");
        Objects.requireNonNull(reference, "reference");
        Objects.requireNonNull(redirectPct, "redirectPct");
        if (redirectPct.signum() < 0 || redirectPct.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("redirectPct must be within 0..1: " + redirectPct);
        }
        Set<String> seen = new HashSet<>();
        for (Debt d : debts) {
            Objects.requireNonNull(d, "debt");
            if (!seen.add(d.id())) {
                throw new IllegalArgumentException("duplicate debt id: " + d.id());
            }
        }
        Map<Integer, List<Debt>> byEnd = new TreeMap<>();
        for (Debt d : debts) {
            Objects.requireNonNull(d, "debt");
            if (d.emisRemaining() > 0) {
                byEnd.computeIfAbsent(d.emisRemaining(), k -> new ArrayList<>()).add(d);
            }
        }
        List<Step> steps = new ArrayList<>();
        CycleCalendar.Cycle cycle = CycleCalendar.cycleFor(reference, startDay);
        int at = 0;
        Money cumFreed = Money.ZERO;
        Money cumRedirected = Money.ZERO;
        for (Map.Entry<Integer, List<Debt>> e : byEnd.entrySet()) {
            while (at < e.getKey()) {
                cycle = CycleCalendar.next(cycle, startDay);
                at++;
            }
            Money freed = Money.ZERO;
            List<String> ids = new ArrayList<>();
            for (Debt d : e.getValue()) {
                freed = freed.plus(d.monthlyEmi());
                ids.add(d.id());
            }
            Money redirected = freed.times(redirectPct);
            cumFreed = cumFreed.plus(freed);
            cumRedirected = cumRedirected.plus(redirected);
            steps.add(new Step(cycle, at, List.copyOf(ids), freed, cumFreed, redirected, cumRedirected));
        }
        return List.copyOf(steps);
    }
}
