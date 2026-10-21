package com.finpath.rules.golden;

import static com.finpath.rules.golden.GoldenAssert.assertWithinOnePercent;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.finpath.rules.AmortisingLoan;
import com.finpath.rules.BulletLoan;
import com.finpath.rules.DebtTrapGuard;
import com.finpath.rules.Loan;
import com.finpath.rules.Money;
import com.finpath.rules.PayoffPlanner;
import com.finpath.rules.PrepaymentSimulator;
import com.finpath.rules.RuleConfig;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    void emiToIncomeRatio() {
        DebtTrapGuard.Result r = DebtTrapGuard.evaluate(Money.ofPaise(fx.paise(GoldenKeys.INCOME_MONTHLY)),
                Money.ofPaise(fx.paise(GoldenKeys.EMI_TOTAL)), RuleConfig.defaults());
        assertWithinOnePercent(GoldenKeys.EMI_RATIO, fx.decimal(GoldenKeys.EMI_RATIO), r.ratio().orElseThrow());
    }

    @Test
    void emiToIncomeRatioAfterExtraEmiReachesBlock() {
        long income = fx.paise(GoldenKeys.INCOME_MONTHLY);
        long emi = fx.paise(GoldenKeys.EMI_TOTAL);
        // Smallest extra EMI that takes the total strictly past the blocked ratio.
        long extra = BigDecimal.valueOf(income).multiply(fx.decimal(GoldenKeys.EMI_BLOCKED_RATIO))
                .setScale(0, RoundingMode.FLOOR).longValueExact() - emi + 1;
        DebtTrapGuard.Result r = DebtTrapGuard.evaluate(Money.ofPaise(income), Money.ofPaise(emi + extra),
                RuleConfig.defaults());
        assertEquals(DebtTrapGuard.Status.BLOCK, r.status());
        assertWithinOnePercent(GoldenKeys.EMI_BLOCKED_RATIO, fx.decimal(GoldenKeys.EMI_BLOCKED_RATIO),
                r.ratio().orElseThrow());
    }
}
