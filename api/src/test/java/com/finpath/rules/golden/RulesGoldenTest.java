package com.finpath.rules.golden;

import static com.finpath.rules.golden.GoldenAssert.assertWithinOnePercent;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.finpath.rules.AmortisingLoan;
import com.finpath.rules.BulletLoan;
import com.finpath.rules.DatedGoal;
import com.finpath.rules.EmergencyFund;
import com.finpath.rules.EmiGuard;
import com.finpath.rules.FiCorpus;
import com.finpath.rules.Loan;
import com.finpath.rules.Money;
import com.finpath.rules.PayoffPlanner;
import com.finpath.rules.PrepaymentSimulator;
import com.finpath.rules.RuleConfig;
import com.finpath.rules.Statistics;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    private BigDecimal homeRate() {
        return fx.decimal(GoldenKeys.HOME_RATE_PCT).movePointLeft(2);
    }

    @Test
    void homeLoanEmiMatchesStatedEmi() {
        Money emi = AmortisingLoan.emi(homePrincipal(), homeRate(), fx.integer(GoldenKeys.HOME_TENURE_MONTHS));
        assertWithinOnePercent(fx.paise(GoldenKeys.HOME_STATED_EMI), emi.paise());
    }

    @Test
    void bulletLoanEffectiveRate() {
        Loan.Bullet loan = new Loan.Bullet(Money.ofPaise(fx.paise(GoldenKeys.BULLET_PRINCIPAL)),
                Money.ofPaise(fx.paise(GoldenKeys.BULLET_MONTHLY_INTEREST)), LocalDate.of(2100, 1, 1));
        BigDecimal pct = BulletLoan.effectiveAnnualRate(loan).movePointRight(2);
        BigDecimal expected = fx.decimal(GoldenKeys.BULLET_EFFECTIVE_RATE_PCT);
        assertWithinOnePercent(GoldenKeys.BULLET_EFFECTIVE_RATE_PCT, expected, pct);
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
        assertWithinOnePercent(fx.paise(GoldenKeys.HOME_INTEREST_REMAINING), s.totalInterest().paise());
    }

    @Test
    void homeLoanEmiToCloseEarly() {
        Money emi = PrepaymentSimulator.emiToClose(homePrincipal(), homeRate(),
                fx.integer(GoldenKeys.HOME_CLOSE_MONTHS));
        assertWithinOnePercent(fx.paise(GoldenKeys.HOME_CLOSE_EMI), emi.paise());
    }

    @Test
    void homeLoanInterestSavedByEarlyClose() {
        PrepaymentSimulator.Outcome o = PrepaymentSimulator.closeIn(homePrincipal(), homeRate(),
                fx.integer(GoldenKeys.HOME_TENURE_MONTHS), fx.integer(GoldenKeys.HOME_CLOSE_MONTHS));
        assertWithinOnePercent(fx.paise(GoldenKeys.HOME_INTEREST_SAVED), o.interestSaved().paise());
    }

    @Test
    void emiToIncomeRatioMatchesFixture() {
        EmiGuard.Result r = EmiGuard.evaluate(Money.ofPaise(fx.paise(GoldenKeys.EMI_TOTAL)),
                Money.ofPaise(fx.paise(GoldenKeys.INCOME_MONTHLY)), RuleConfig.defaults());
        assertWithinOnePercent(GoldenKeys.EMI_RATIO, fx.decimal(GoldenKeys.EMI_RATIO), r.ratio());
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
        assertWithinOnePercent(fx.paise(GoldenKeys.EMERGENCY_ONE_MONTH), EmergencyFund.target(outflow, 1).paise());
        assertWithinOnePercent(fx.paise(GoldenKeys.EMERGENCY_THREE_MONTH), EmergencyFund.target(outflow, 3).paise());
    }

    @Test
    void emergencyFundMonthsCovered() {
        BigDecimal covered = EmergencyFund.monthsCovered(Money.ofPaise(fx.paise(GoldenKeys.EMERGENCY_CURRENT)),
                Money.ofPaise(fx.paise(GoldenKeys.OUTFLOW_MONTHLY)));
        assertWithinOnePercent(GoldenKeys.EMERGENCY_MONTHS_COVERED, fx.decimal(GoldenKeys.EMERGENCY_MONTHS_COVERED),
                covered);
    }

    @Test
    void datedGoalMonthlyNeed() {
        LocalDate ref = LocalDate.of(2026, 10, 25);
        LocalDate due = ref.plusMonths(fx.integer(GoldenKeys.GOAL_CYCLES));
        DatedGoal.Result r = DatedGoal.evaluate(Money.ofPaise(fx.paise(GoldenKeys.GOAL_TARGET)), Money.ZERO,
                Money.ZERO, ref, due, 25, RuleConfig.defaults());
        assertEquals(fx.integer(GoldenKeys.GOAL_CYCLES), r.cyclesRemaining());
        assertWithinOnePercent(fx.paise(GoldenKeys.GOAL_MONTHLY), r.monthlyNeed().paise());
    }

    @Test
    void fiCorpusInflatedExpenseGrossAndNet() {
        int years = fx.integer(GoldenKeys.FI_HORIZON_YEARS);
        FiCorpus.Result r = FiCorpus.corpus(Money.ofPaise(fx.paise(GoldenKeys.FI_TODAY_EXPENSE)),
                Money.ofPaise(fx.paise(GoldenKeys.FI_PASSIVE_MONTHLY)), years, RuleConfig.defaults());
        assertWithinOnePercent(fx.paise(GoldenKeys.FI_INFLATED_EXPENSE), r.inflatedMonthlyExpense().paise());
        assertWithinOnePercent(fx.paise(GoldenKeys.FI_GROSS_CORPUS), r.grossCorpus().paise());
        assertWithinOnePercent(fx.paise(GoldenKeys.FI_NET_CORPUS), r.netCorpus().paise());
    }

    @Test
    void savingsRateIsSurplusOverIncome() {
        BigDecimal rate = Statistics.savingsRate(Money.ofPaise(fx.paise(GoldenKeys.SURPLUS_MONTHLY)),
                Money.ofPaise(fx.paise(GoldenKeys.INCOME_MONTHLY)));
        assertWithinOnePercent(GoldenKeys.SAVINGS_RATE_PCT, fx.decimal(GoldenKeys.SAVINGS_RATE_PCT), rate);
    }
}
