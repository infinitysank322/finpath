package com.finpath.rules;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Surplus waterfall (FR-22). Pure: surplus = income less mandatory outflows (step 1), and the surplus
 * is then spent in priority order:
 * Bullet loans are always funded in step 3 through their sinking-fund deposit, whatever their rate; a
 * high-rate debt may absorb its whole balance and starve later steps by design.
 * <ol start="2">
 *   <li>emergency fund up to one month of outflow</li>
 *   <li>bullet-loan sinking-fund deposits, then debt above {@link RuleConfig#HIGH_INTEREST_DEBT_RATE}
 *       in avalanche order (highest rate first, id breaks ties)</li>
 *   <li>emergency fund up to the target months</li>
 *   <li>dated goals held in safe instruments, in the order given</li>
 *   <li>minimum habit SIP ({@link RuleConfig#minHabitSipPaise()})</li>
 *   <li>remainder split by the user's ratio between prepaying debt below the high-interest rate
 *       (avalanche, capped at what the debt can absorb) and long-term SIPs</li>
 * </ol>
 * Each step takes at most what it needs and at most what is left, so the lines always sum exactly to
 * the surplus (the SIP line takes any remainder). Goals that allow equity are not given a step-5
 * line; their monthly need is met from the step-7 SIP remainder. The default prepayment share is
 * {@link RuleConfig#DEFAULT_PREPAY_SHARE}. A negative surplus allocates nothing and is
 * reported as a shortfall. EPF and other deductions outside in-hand salary are not inputs.
 */
public final class WaterfallAllocator {

    private WaterfallAllocator() {
    }

    public enum Step {
        EMERGENCY_FUND_ONE_MONTH, HIGH_INTEREST_DEBT, EMERGENCY_FUND_TARGET, SAFE_GOALS, HABIT_SIP,
        DEBT_PREPAYMENT, LONG_TERM_SIP
    }

    /**
     * A debt that can absorb money this cycle. {@code absorbable} is the sinking-fund deposit for a
     * bullet loan, or the outstanding balance otherwise.
     */
    public record DebtTarget(String id, BigDecimal annualRate, boolean bullet, Money absorbable) {
        public DebtTarget {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(annualRate, "annualRate");
            Objects.requireNonNull(absorbable, "absorbable");
            if (annualRate.signum() < 0 || absorbable.isNegative()) {
                throw new IllegalArgumentException("rate and amount must not be negative");
            }
        }
    }

    /** A dated goal's monthly need and the instrument class its horizon allows. */
    public record GoalTarget(String id, Money monthlyNeed, DatedGoal.InstrumentClass instrument) {
        public GoalTarget {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(monthlyNeed, "monthlyNeed");
            Objects.requireNonNull(instrument, "instrument");
            if (monthlyNeed.isNegative()) {
                throw new IllegalArgumentException("monthlyNeed must not be negative");
            }
        }
    }

    /**
     * @param income in-hand monthly income
     * @param mandatoryOutflows EMIs, fixed expenses, insurance premiums (step 1)
     * @param monthlyOutflow what the emergency fund is sized from
     * @param emergencyFundCurrent already saved
     * @param emergencyFundTargetMonths the 3-6 month target
     * @param debts every open debt
     * @param goals dated goals in priority order
     * @param prepaySharePct share (0..1) of the final remainder aimed at debt prepayment
     */
    public record Input(Money income, List<Money> mandatoryOutflows, Money monthlyOutflow,
                        Money emergencyFundCurrent, int emergencyFundTargetMonths, List<DebtTarget> debts,
                        List<GoalTarget> goals, BigDecimal prepaySharePct) {
        public Input {
            Objects.requireNonNull(income, "income");
            Objects.requireNonNull(monthlyOutflow, "monthlyOutflow");
            Objects.requireNonNull(emergencyFundCurrent, "emergencyFundCurrent");
            Objects.requireNonNull(prepaySharePct, "prepaySharePct");
            mandatoryOutflows = List.copyOf(Objects.requireNonNull(mandatoryOutflows, "mandatoryOutflows"));
            debts = List.copyOf(Objects.requireNonNull(debts, "debts"));
            goals = List.copyOf(Objects.requireNonNull(goals, "goals"));
            if (emergencyFundTargetMonths <= 0) {
                throw new IllegalArgumentException("emergencyFundTargetMonths must be positive");
            }
            if (prepaySharePct.signum() < 0 || prepaySharePct.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("prepaySharePct must be between 0 and 1");
            }
            if (income.isNegative() || monthlyOutflow.isNegative() || emergencyFundCurrent.isNegative()) {
                throw new IllegalArgumentException("amounts must not be negative");
            }
        }
    }

    /** One allocation; {@code ref} is the debt/goal id, or null for fund and SIP steps. */
    public record Line(Step step, String ref, Money amount) {
    }

    public record Result(Money surplus, Money shortfall, List<Line> lines) {
        public Result {
            lines = Collections.unmodifiableList(new ArrayList<>(lines));
        }

        public Money total() {
            return Money.ofPaise(lines.stream().mapToLong(l -> l.amount().paise()).sum());
        }
    }

    public static Result allocate(Input in, RuleConfig config) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(config, "config");
        long mandatory = 0;
        for (Money m : in.mandatoryOutflows()) {
            if (m.isNegative()) {
                throw new IllegalArgumentException("mandatory outflow must not be negative");
            }
            mandatory = Math.addExact(mandatory, m.paise());
        }
        long surplus = in.income().paise();
        surplus = Math.subtractExact(surplus, mandatory);
        if (surplus <= 0) {
            return new Result(Money.ofPaise(surplus), Money.ofPaise(-surplus), List.of());
        }
        Spend s = new Spend(surplus);

        long efSaved = in.emergencyFundCurrent().paise();
        long oneMonth = EmergencyFund.target(in.monthlyOutflow(), 1).paise();
        long efStep2 = s.take(Step.EMERGENCY_FUND_ONE_MONTH, null, Math.max(0, oneMonth - efSaved));
        efSaved += efStep2;

        List<DebtTarget> high = new ArrayList<>();
        List<DebtTarget> low = new ArrayList<>();
        for (DebtTarget d : in.debts()) {
            if (d.bullet() || d.annualRate().compareTo(RuleConfig.HIGH_INTEREST_DEBT_RATE) > 0) {
                high.add(d);
            } else {
                low.add(d);
            }
        }
        Comparator<DebtTarget> avalanche = Comparator.comparing(DebtTarget::annualRate).reversed()
                .thenComparing(DebtTarget::id);
        high.sort(Comparator.comparing((DebtTarget d) -> !d.bullet()).thenComparing(avalanche));
        low.sort(avalanche);
        for (DebtTarget d : high) {
            s.take(Step.HIGH_INTEREST_DEBT, d.id(), d.absorbable().paise());
        }

        long target = EmergencyFund.target(in.monthlyOutflow(), in.emergencyFundTargetMonths()).paise();
        s.take(Step.EMERGENCY_FUND_TARGET, null, Math.max(0, target - efSaved));

        for (GoalTarget g : in.goals()) {
            if (g.instrument() == DatedGoal.InstrumentClass.SAFE_ONLY) {
                s.take(Step.SAFE_GOALS, g.id(), g.monthlyNeed().paise());
            }
        }

        s.take(Step.HABIT_SIP, null, config.minHabitSipPaise());

        long remainder = s.left;
        long wantPrepay = Money.ofPaise(remainder).times(in.prepaySharePct()).paise();
        for (DebtTarget d : low) {
            wantPrepay -= s.take(Step.DEBT_PREPAYMENT, d.id(), Math.min(wantPrepay, d.absorbable().paise()));
        }
        s.take(Step.LONG_TERM_SIP, null, s.left);
        return new Result(Money.ofPaise(surplus), Money.ZERO, s.lines);
    }

    /** Running balance; {@code take} grants min(wanted, left) and records non-zero lines. */
    private static final class Spend {
        long left;
        final List<Line> lines = new ArrayList<>();

        Spend(long surplus) {
            this.left = surplus;
        }

        long take(Step step, String ref, long wanted) {
            long got = Math.min(Math.max(0, wanted), left);
            if (got > 0) {
                left -= got;
                lines.add(new Line(step, ref, Money.ofPaise(got)));
            }
            return got;
        }
    }
}
