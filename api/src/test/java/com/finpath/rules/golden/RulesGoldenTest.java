package com.finpath.rules.golden;

import static com.finpath.rules.golden.GoldenAssert.assertWithinOnePercent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.finpath.rules.AmortisingLoan;
import com.finpath.rules.BulletLoan;
import com.finpath.rules.DatedGoal;
import com.finpath.rules.CycleCalendar;
import com.finpath.rules.EmergencyFund;
import com.finpath.rules.EmiGuard;
import com.finpath.rules.EmiRollOff;
import com.finpath.rules.FiCorpus;
import com.finpath.rules.IncomeCalculator;
import com.finpath.rules.Loan;
import com.finpath.rules.Money;
import com.finpath.rules.PayoffPlanner;
import com.finpath.rules.PrepaymentSimulator;
import com.finpath.rules.RecurringSplit;
import com.finpath.rules.RuleConfig;
import com.finpath.rules.SinkingFund;
import com.finpath.rules.Statistics;
import com.finpath.rules.WaterfallAllocator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Persona golden figures. Every expected value is read from the fixture (real file if present,
 * else the fake sample); no amount is written in this file. Tolerance comes only from
 * {@link GoldenAssert}. Each later rule task adds its assertions here.
 */
class RulesGoldenTest {

    private final GoldenFixture fx = GoldenFixture.shared();

    private Money homePrincipal() {
        return Money.ofPaise(fx.paise(GoldenKeys.HOME_PRINCIPAL));
    }


    private static final int START_DAY = 25;
    /** Pinned reference date (the clock is never read); same pin as the dated-goal test. */
    private static final LocalDate REFERENCE = LocalDate.of(2026, 10, 25);

    /** Keys that are rule INPUTS: read from the fixture to drive a rule, not an engine output. */
    private static final Set<String> INPUT_KEYS = Set.of(
            GoldenKeys.HOME_PRINCIPAL, GoldenKeys.HOME_RATE_PCT, GoldenKeys.HOME_TENURE_MONTHS,
            GoldenKeys.HOME_CLOSE_MONTHS, GoldenKeys.BULLET_PRINCIPAL, GoldenKeys.BULLET_MONTHLY_INTEREST,
            GoldenKeys.INCOME_MONTHLY, GoldenKeys.INCOME_SALARY, GoldenKeys.INCOME_RENTAL,
            GoldenKeys.INCOME_BONUS, GoldenKeys.EMI_TOTAL, GoldenKeys.OUTFLOW_MONTHLY,
            GoldenKeys.EMERGENCY_CURRENT, GoldenKeys.GOAL_TARGET, GoldenKeys.FI_TODAY_EXPENSE,
            GoldenKeys.FI_HORIZON_YEARS, GoldenKeys.FI_PASSIVE_MONTHLY,
            GoldenKeys.BUDGET_CATEGORY, GoldenKeys.BUDGET_SPENT, GoldenKeys.BUDGET_SPENT_WARN,
            GoldenKeys.BUDGET_SPENT_BREACH,
            GoldenKeys.LOAN_HOME_PRINCIPAL, GoldenKeys.LOAN_HOME_RATE, GoldenKeys.LOAN_HOME_EMIS_LEFT,
            GoldenKeys.LOAN_GOLD_PRINCIPAL, GoldenKeys.LOAN_GOLD_RATE);

    private static final Set<String> ASSERTED = new HashSet<>();
    private static int testsRun;

    @BeforeEach
    void countTest() {
        testsRun++;
    }

    private void expectPaise(String key, long actual) {
        ASSERTED.add(key);
        assertWithinOnePercent(fx.paise(key), actual);
    }

    private void expectDecimal(String key, BigDecimal actual) {
        ASSERTED.add(key);
        assertWithinOnePercent(key, fx.decimal(key), actual);
    }

    /** Exact: counts. */
    private void expectInt(String key, int actual) {
        ASSERTED.add(key);
        assertTrue(fx.integer(key) == actual, "figure " + key + " mismatch (values withheld)");
    }

    /** Exact: cycle ids (yyyy-MM). */
    private void expectCycleId(String key, YearMonth actual) {
        ASSERTED.add(key);
        assertTrue(fx.text(key).equals(actual.toString()), "figure " + key + " mismatch (values withheld)");
    }

    private static int monthsBetween(YearMonth from, YearMonth to) {
        return (int) ChronoUnit.MONTHS.between(from, to);
    }

    private static LocalDate cycleStart(YearMonth id) {
        return id.atDay(START_DAY);
    }

    /** Debts whose last EMI ends so that the freed EMI first lands in the fixture's step cycles. */
    private List<EmiRollOff.Debt> rollOffDebts() {
        YearMonth ref = YearMonth.from(CycleCalendar.cycleFor(REFERENCE, START_DAY).start());
        String[][] cycleKeys = {{GoldenKeys.ROLLOFF_1, GoldenKeys.ROLLOFF_1_CYCLE},
                {GoldenKeys.ROLLOFF_2, GoldenKeys.ROLLOFF_2_CYCLE},
                {GoldenKeys.ROLLOFF_3, GoldenKeys.ROLLOFF_3_CYCLE}};
        List<EmiRollOff.Debt> debts = new ArrayList<>();
        for (int i = 0; i < cycleKeys.length; i++) {
            int emis = monthsBetween(ref, YearMonth.parse(fx.text(cycleKeys[i][1])));
            debts.add(new EmiRollOff.Debt("debt" + (i + 1), Money.ofPaise(fx.paise(cycleKeys[i][0])), emis));
        }
        return debts;
    }

    private Loan.Bullet bulletDueInFixtureCycle() {
        return new Loan.Bullet(Money.ofPaise(fx.paise(GoldenKeys.BULLET_PRINCIPAL)),
                Money.ofPaise(fx.paise(GoldenKeys.BULLET_MONTHLY_INTEREST)),
                cycleStart(YearMonth.parse(fx.text(GoldenKeys.BULLET_DUE_CYCLE))));
    }

    private WaterfallAllocator.Result allocate(long incomePaise, long mandatoryPaise) {
        return WaterfallAllocator.allocate(new WaterfallAllocator.Input(
                Money.ofPaise(incomePaise), List.of(Money.ofPaise(mandatoryPaise)),
                Money.ofPaise(fx.paise(GoldenKeys.OUTFLOW_MONTHLY)),
                Money.ofPaise(fx.paise(GoldenKeys.EMERGENCY_CURRENT)), 3, List.of(), List.of(),
                BigDecimal.ZERO), RuleConfig.defaults());
    }

    @Test
    void sinkingFundMonthlySetAside() {
        Loan.Bullet loan = bulletDueInFixtureCycle();
        SinkingFund.Result r = SinkingFund.evaluate(loan.principal(), Money.ZERO, REFERENCE, loan.dueDate(),
                REFERENCE, START_DAY);
        expectPaise(GoldenKeys.SINKING_MONTHLY, r.setAsidePerCycle().paise());
        expectInt(GoldenKeys.BULLET_CYCLES_REMAINING, r.cyclesRemaining());
    }

    @Test
    void bulletDueCycleIsReferenceCycleShiftedByCyclesRemaining() {
        LocalDate due = REFERENCE.plusMonths(fx.integer(GoldenKeys.BULLET_CYCLES_REMAINING));
        expectCycleId(GoldenKeys.BULLET_DUE_CYCLE, YearMonth.from(CycleCalendar.cycleFor(due, START_DAY).start()));
    }

    @Test
    void emiBlockThresholdMatchesFixture() {
        expectDecimal(GoldenKeys.EMI_BLOCKED_RATIO, RuleConfig.defaults().emiToIncomeBlock());
    }

    @Test
    void rollOffStepsAmountsAndCycles() {
        List<EmiRollOff.Step> steps = EmiRollOff.timeline(rollOffDebts(), REFERENCE, START_DAY);
        assertEquals(3, steps.size());
        expectPaise(GoldenKeys.ROLLOFF_1, steps.get(0).freed().paise());
        expectCycleId(GoldenKeys.ROLLOFF_1_CYCLE, YearMonth.from(steps.get(0).cycleId()));
        expectPaise(GoldenKeys.ROLLOFF_2, steps.get(1).freed().paise());
        expectCycleId(GoldenKeys.ROLLOFF_2_CYCLE, YearMonth.from(steps.get(1).cycleId()));
        expectPaise(GoldenKeys.ROLLOFF_3, steps.get(2).freed().paise());
        expectCycleId(GoldenKeys.ROLLOFF_3_CYCLE, YearMonth.from(steps.get(2).cycleId()));
    }

    /**
     * Mandatory outflow is derived as income minus the fixture's monthly surplus (the fixture has
     * no separate mandatory-outflow key); the allocator must hand that surplus back, and must
     * show no shortfall.
     */
    @Test
    void monthlySurplusFromWaterfall() {
        long income = fx.paise(GoldenKeys.INCOME_MONTHLY);
        WaterfallAllocator.Result r = allocate(income, income - fx.paise(GoldenKeys.SURPLUS_MONTHLY));
        expectPaise(GoldenKeys.SURPLUS_MONTHLY, r.surplus().paise());
        assertEquals(0L, r.shortfall().paise());
    }

    /** Surplus after the last roll-off step: current surplus plus the redirected freed EMIs (100%). */
    @Test
    void laterSurplusAfterRollOffAndItsCycle() {
        List<EmiRollOff.Step> steps = EmiRollOff.timeline(rollOffDebts(), REFERENCE, START_DAY);
        EmiRollOff.Step last = steps.get(steps.size() - 1);
        long income = fx.paise(GoldenKeys.INCOME_MONTHLY);
        long mandatoryNow = income - fx.paise(GoldenKeys.SURPLUS_MONTHLY);
        // A freed EMI leaves the mandatory line; modelled as the same amount added to income so the
        // mandatory outflow can never go negative.
        WaterfallAllocator.Result later = allocate(income + last.cumulativeRedirected().paise(), mandatoryNow);
        expectPaise(GoldenKeys.SURPLUS_LATER, later.surplus().paise());
        expectCycleId(GoldenKeys.SURPLUS_LATER_CYCLE, YearMonth.from(last.cycleId()));
    }

    /**
     * Runs after every test: fails the class if any non-input fixture key was never asserted.
     * Skipped when only some tests ran (a single method was selected).
     */
    @AfterAll
    static void everyOutputKeyIsAsserted() {
        long declared = java.util.Arrays.stream(RulesGoldenTest.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Test.class)).count();
        if (testsRun < declared) {
            return;
        }
        assertTrue(GoldenKeys.ALL.containsAll(INPUT_KEYS), "INPUT_KEYS lists an unknown key");
        Set<String> missing = new HashSet<>(GoldenKeys.ALL);
        missing.removeAll(INPUT_KEYS);
        missing.removeAll(ASSERTED);
        assertTrue(missing.isEmpty(), "Golden keys never asserted: " + new java.util.TreeSet<>(missing));
    }

    private BigDecimal homeRate() {
        return fx.decimal(GoldenKeys.HOME_RATE_PCT).movePointLeft(2);
    }

    @Test
    void homeLoanEmiMatchesStatedEmi() {
        Money emi = AmortisingLoan.emi(homePrincipal(), homeRate(), fx.integer(GoldenKeys.HOME_TENURE_MONTHS));
        expectPaise(GoldenKeys.HOME_STATED_EMI, emi.paise());
    }

    @Test
    void bulletLoanEffectiveRate() {
        Loan.Bullet loan = new Loan.Bullet(Money.ofPaise(fx.paise(GoldenKeys.BULLET_PRINCIPAL)),
                Money.ofPaise(fx.paise(GoldenKeys.BULLET_MONTHLY_INTEREST)), LocalDate.of(2100, 1, 1));
        BigDecimal pct = BulletLoan.effectiveAnnualRate(loan).movePointRight(2);
        expectDecimal(GoldenKeys.BULLET_EFFECTIVE_RATE_PCT, pct);
    }

    @Test
    void avalancheOrdersHighRateBulletLoanBeforeHomeLoan() {
        Loan.Bullet bullet = new Loan.Bullet(Money.ofPaise(fx.paise(GoldenKeys.BULLET_PRINCIPAL)),
                Money.ofPaise(fx.paise(GoldenKeys.BULLET_MONTHLY_INTEREST)), LocalDate.of(2100, 1, 1));
        PayoffPlanner.DebtEntry bulletEntry = new PayoffPlanner.DebtEntry("bullet",
                BulletLoan.effectiveAnnualRate(bullet), bullet.principal(),
                Money.ofPaise(fx.paise(GoldenKeys.BULLET_MONTHLY_INTEREST)));
        PayoffPlanner.DebtEntry home = new PayoffPlanner.DebtEntry("home", homeRate(), homePrincipal(),
                Money.ofPaise(fx.paise(GoldenKeys.HOME_STATED_EMI)));
        // Input order deliberately puts the lower-rate loan first.
        List<PayoffPlanner.DebtEntry> ordered =
                PayoffPlanner.order(List.of(home, bulletEntry), PayoffPlanner.Strategy.AVALANCHE);
        assertEquals(List.of("bullet", "home"), ordered.stream().map(PayoffPlanner.DebtEntry::id).toList());
        assertEquals(List.of("bullet", "home"), PayoffPlanner
                .plan(List.of(home, bulletEntry), PayoffPlanner.Strategy.AVALANCHE).order());
    }

    @Test
    void homeLoanInterestRemaining() {
        AmortisingLoan.Schedule s = AmortisingLoan.schedule(homePrincipal(), homeRate(),
                fx.integer(GoldenKeys.HOME_TENURE_MONTHS));
        expectPaise(GoldenKeys.HOME_INTEREST_REMAINING, s.totalInterest().paise());
    }

    @Test
    void homeLoanEmiToCloseEarly() {
        Money emi = PrepaymentSimulator.emiToClose(homePrincipal(), homeRate(),
                fx.integer(GoldenKeys.HOME_CLOSE_MONTHS));
        expectPaise(GoldenKeys.HOME_CLOSE_EMI, emi.paise());
    }

    @Test
    void homeLoanInterestSavedByEarlyClose() {
        PrepaymentSimulator.Outcome o = PrepaymentSimulator.closeIn(homePrincipal(), homeRate(),
                fx.integer(GoldenKeys.HOME_TENURE_MONTHS), fx.integer(GoldenKeys.HOME_CLOSE_MONTHS));
        expectPaise(GoldenKeys.HOME_INTEREST_SAVED, o.interestSaved().paise());
    }

    @Test
    void emiToIncomeRatioMatchesFixture() {
        EmiGuard.Result r = EmiGuard.evaluate(Money.ofPaise(fx.paise(GoldenKeys.EMI_TOTAL)),
                Money.ofPaise(fx.paise(GoldenKeys.INCOME_MONTHLY)), RuleConfig.defaults());
        expectDecimal(GoldenKeys.EMI_RATIO, r.ratio());
    }

    @Test
    void extraEmiTripsTheGuard() {
        Money income = Money.ofPaise(fx.paise(GoldenKeys.INCOME_MONTHLY));
        EmiGuard.Result base = EmiGuard.evaluate(Money.ofPaise(fx.paise(GoldenKeys.EMI_TOTAL)), income,
                RuleConfig.defaults());
        // Take on exactly the headroom plus one paise: the guard must block.
        Money tripped = Money.ofPaise(fx.paise(GoldenKeys.EMI_TOTAL) + base.headroom().paise() + 1);
        assertEquals(EmiGuard.Status.BLOCK, EmiGuard.evaluate(tripped, income, RuleConfig.defaults()).status());
    }

    @Test
    void emergencyFundTargets() {
        Money outflow = Money.ofPaise(fx.paise(GoldenKeys.OUTFLOW_MONTHLY));
        expectPaise(GoldenKeys.EMERGENCY_ONE_MONTH, EmergencyFund.target(outflow, 1).paise());
        expectPaise(GoldenKeys.EMERGENCY_THREE_MONTH, EmergencyFund.target(outflow, 3).paise());
    }

    @Test
    void emergencyFundMonthsCovered() {
        BigDecimal covered = EmergencyFund.monthsCovered(Money.ofPaise(fx.paise(GoldenKeys.EMERGENCY_CURRENT)),
                Money.ofPaise(fx.paise(GoldenKeys.OUTFLOW_MONTHLY)));
        expectDecimal(GoldenKeys.EMERGENCY_MONTHS_COVERED, covered);
    }

    @Test
    void datedGoalMonthlyNeed() {
        LocalDate ref = LocalDate.of(2026, 10, 25);
        LocalDate due = ref.plusMonths(fx.integer(GoldenKeys.GOAL_CYCLES));
        DatedGoal.Result r = DatedGoal.evaluate(Money.ofPaise(fx.paise(GoldenKeys.GOAL_TARGET)), Money.ZERO,
                Money.ZERO, ref, due, 25, RuleConfig.defaults());
        expectInt(GoldenKeys.GOAL_CYCLES, r.cyclesRemaining());
        expectPaise(GoldenKeys.GOAL_MONTHLY, r.monthlyNeed().paise());
    }

    @Test
    void fiCorpusInflatedExpenseGrossAndNet() {
        int years = fx.integer(GoldenKeys.FI_HORIZON_YEARS);
        FiCorpus.Result r = FiCorpus.corpus(Money.ofPaise(fx.paise(GoldenKeys.FI_TODAY_EXPENSE)),
                Money.ofPaise(fx.paise(GoldenKeys.FI_PASSIVE_MONTHLY)), years, RuleConfig.defaults());
        expectPaise(GoldenKeys.FI_INFLATED_EXPENSE, r.inflatedMonthlyExpense().paise());
        expectPaise(GoldenKeys.FI_GROSS_CORPUS, r.grossCorpus().paise());
        expectPaise(GoldenKeys.FI_NET_CORPUS, r.netCorpus().paise());
    }

    private List<IncomeCalculator.Source> incomeSources(boolean withBonus) {
        LocalDate longAgo = REFERENCE.minusYears(3);
        List<IncomeCalculator.Source> list = new ArrayList<>(List.of(
                new IncomeCalculator.Source(IncomeCalculator.Type.SALARY, fx.paise(GoldenKeys.INCOME_SALARY),
                        IncomeCalculator.Frequency.MONTHLY, longAgo, null),
                new IncomeCalculator.Source(IncomeCalculator.Type.RENTAL, fx.paise(GoldenKeys.INCOME_RENTAL),
                        IncomeCalculator.Frequency.MONTHLY, longAgo, null)));
        if (withBonus) {
            list.add(new IncomeCalculator.Source(IncomeCalculator.Type.BONUS, fx.paise(GoldenKeys.INCOME_BONUS),
                    IncomeCalculator.Frequency.ONE_OFF, REFERENCE.plusDays(1), null));
        }
        return list;
    }

    @Test
    void salaryPlusRentalEqualsMonthlyIncome() {
        assertEquals(fx.paise(GoldenKeys.INCOME_MONTHLY),
                fx.paise(GoldenKeys.INCOME_SALARY) + fx.paise(GoldenKeys.INCOME_RENTAL),
                "fixture salary + rental must equal income.monthly_paise");
        CycleCalendar.Cycle cycle = CycleCalendar.cycleFor(REFERENCE, START_DAY);
        long total = IncomeCalculator.cycleTotal(incomeSources(false), cycle);
        assertWithinOnePercent(fx.paise(GoldenKeys.INCOME_MONTHLY), total);
    }

    @Test
    void oneOffBonusLiftsOnlyItsOwnCycle() {
        CycleCalendar.Cycle cycle = CycleCalendar.cycleFor(REFERENCE, START_DAY);
        CycleCalendar.Cycle prev = CycleCalendar.cycleFor(cycle.start().minusDays(1), START_DAY);
        CycleCalendar.Cycle next = CycleCalendar.next(cycle, START_DAY);
        List<IncomeCalculator.Source> sources = incomeSources(true);
        long monthly = fx.paise(GoldenKeys.INCOME_MONTHLY);
        assertWithinOnePercent(monthly + fx.paise(GoldenKeys.INCOME_BONUS),
                IncomeCalculator.cycleTotal(sources, cycle));
        assertWithinOnePercent(monthly, IncomeCalculator.cycleTotal(sources, prev));
        assertWithinOnePercent(monthly, IncomeCalculator.cycleTotal(sources, next));
    }

    /** Three consecutive quarterly instalments add up to the fixture's bill exactly (remainder on the last). */
    @Test
    void quarterlyBillInstalmentsSumToTheBill() {
        long bill = fx.paise(GoldenKeys.QUARTERLY_BILL);
        int periods = RecurringSplit.periodMonths(RecurringSplit.Frequency.QUARTERLY);
        long sum = 0;
        CycleCalendar.Cycle cycle = CycleCalendar.cycleFor(REFERENCE, START_DAY);
        RecurringSplit.Template t = new RecurringSplit.Template(bill, RecurringSplit.Frequency.QUARTERLY,
                cycle.start(), null);
        for (int i = 0; i < periods; i++) {
            sum += RecurringSplit.due(t, cycle, START_DAY).orElseThrow().amountPaise();
            cycle = CycleCalendar.next(cycle, START_DAY);
        }
        ASSERTED.add(GoldenKeys.QUARTERLY_BILL);
        assertTrue(sum == bill, "figure " + GoldenKeys.QUARTERLY_BILL + " mismatch (values withheld)");
    }

    @Test
    void savingsRateIsSurplusOverIncome() {
        BigDecimal rate = Statistics.savingsRate(Money.ofPaise(fx.paise(GoldenKeys.SURPLUS_MONTHLY)),
                Money.ofPaise(fx.paise(GoldenKeys.INCOME_MONTHLY)));
        expectDecimal(GoldenKeys.SAVINGS_RATE_PCT, rate);
    }

    /** Budget status for each fixture spend: level from the thresholds, remaining and fraction exact. */
    @Test
    void budgetStatusForFixtureSpends() {
        Money budget = Money.ofPaise(fx.paise(GoldenKeys.BUDGET_CATEGORY));
        Object[][] cases = {{GoldenKeys.BUDGET_SPENT, com.finpath.rules.BudgetRules.Level.OK},
                {GoldenKeys.BUDGET_SPENT_WARN, com.finpath.rules.BudgetRules.Level.WARN},
                {GoldenKeys.BUDGET_SPENT_BREACH, com.finpath.rules.BudgetRules.Level.BREACH}};
        for (Object[] c : cases) {
            long spent = fx.paise((String) c[0]);
            com.finpath.rules.BudgetRules.Status st = com.finpath.rules.BudgetRules.status(budget,
                    Money.ofPaise(spent), RuleConfig.defaults());
            assertEquals(c[1], st.level(), "budget level mismatch for " + c[0]);
            assertEquals(budget.paise() - spent, st.remaining().paise(), "remaining mismatch for " + c[0]);
            assertEquals(0, BigDecimal.valueOf(spent)
                    .divide(BigDecimal.valueOf(budget.paise()), 4, java.math.RoundingMode.HALF_UP)
                    .compareTo(st.usedFraction().setScale(4, java.math.RoundingMode.HALF_UP)),
                    "fraction mismatch for " + c[0]);
            ASSERTED.add((String) c[0]);
        }
        ASSERTED.add(GoldenKeys.BUDGET_CATEGORY);
    }

    /** T48: the stated home EMI is close to, but not equal to, the computed EMI; the stated figure wins. */
    @Test
    void loanStatedHomeEmiDiffersFromTheComputedEmi() {
        Money computed = AmortisingLoan.emi(Money.ofPaise(fx.paise(GoldenKeys.LOAN_HOME_PRINCIPAL)),
                fx.decimal(GoldenKeys.LOAN_HOME_RATE), fx.integer(GoldenKeys.LOAN_HOME_EMIS_LEFT));
        assertTrue(computed.paise() != fx.paise(GoldenKeys.LOAN_HOME_EMI_STATED),
                "fixture must keep the stated EMI different from the computed one (values withheld)");
        expectPaise(GoldenKeys.LOAN_HOME_EMI_STATED, computed.paise());
        ASSERTED.add(GoldenKeys.LOAN_HOME_EMIS_LEFT);
    }

    /** T48: derived bullet interest matches the stated monthly interest of the gold loan. */
    @Test
    void loanGoldMonthlyInterestMatchesTheDerivedInterest() {
        Money derived = BulletLoan.monthlyInterest(Money.ofPaise(fx.paise(GoldenKeys.LOAN_GOLD_PRINCIPAL)),
                fx.decimal(GoldenKeys.LOAN_GOLD_RATE));
        expectPaise(GoldenKeys.LOAN_GOLD_MONTHLY_INTEREST, derived.paise());
    }

    /** T49: home-loan interest remaining comes from the STATED EMI, not the computed one. */
    @Test
    void loanDetailHomeUsesStatedEmiForScheduleAndInterest() {
        Money principal = Money.ofPaise(fx.paise(GoldenKeys.LOAN_HOME_PRINCIPAL));
        BigDecimal rate = fx.decimal(GoldenKeys.LOAN_HOME_RATE);
        int left = fx.integer(GoldenKeys.LOAN_HOME_EMIS_LEFT);
        Money stated = Money.ofPaise(fx.paise(GoldenKeys.LOAN_HOME_EMI_STATED));
        com.finpath.rules.LoanDetail.Result r = com.finpath.rules.LoanDetail.compute(
                com.finpath.rules.LoanType.EMI, principal, rate, stated, left, null, null, REFERENCE, START_DAY);
        assertEquals(stated, r.emiUsed());
        assertFalse(r.emiDerived());
        assertEquals(AmortisingLoan.emi(principal, rate, left), r.computedEmi().orElseThrow());
        assertEquals(AmortisingLoan.schedule(principal, rate, stated, left).totalInterest(), r.interestRemaining());
        // Persona-specific: the stated home-loan EMI is within 1% of the computed one, so its interest is too.
        assertWithinOnePercent(AmortisingLoan.schedule(principal, rate, left).totalInterest().paise(),
                r.interestRemaining().paise());
    }

    /** T49: gold-loan effective annual rate through the facade equals the bullet golden rate. */
    @Test
    void loanDetailGoldEffectiveRate() {
        Money principal = Money.ofPaise(fx.paise(GoldenKeys.LOAN_GOLD_PRINCIPAL));
        Money interest = Money.ofPaise(fx.paise(GoldenKeys.LOAN_GOLD_MONTHLY_INTEREST));
        com.finpath.rules.LoanDetail.Result r = com.finpath.rules.LoanDetail.compute(
                com.finpath.rules.LoanType.BULLET, principal, fx.decimal(GoldenKeys.LOAN_GOLD_RATE), interest,
                null, REFERENCE.plusYears(10), null, REFERENCE, START_DAY);
        expectDecimal(GoldenKeys.BULLET_EFFECTIVE_RATE_PCT, r.effectiveAnnualRate().movePointRight(2));
        assertWithinOnePercent(fx.decimal(GoldenKeys.LOAN_GOLD_RATE), r.effectiveAnnualRate());
        assertEquals(interest, r.emiUsed());
    }
}
