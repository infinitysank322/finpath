package com.finpath.rules.golden;

import static com.finpath.rules.golden.GoldenAssert.assertWithinOnePercent;

import com.finpath.rules.AmortisingLoan;
import com.finpath.rules.BulletLoan;
import com.finpath.rules.Loan;
import com.finpath.rules.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
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
    void homeLoanInterestRemaining() {
        AmortisingLoan.Schedule s = AmortisingLoan.schedule(homePrincipal(), homeRate(),
                fx.integer(GoldenKeys.HOME_TENURE_MONTHS));
        assertWithinOnePercent(fx.paise(GoldenKeys.HOME_INTEREST_REMAINING), s.totalInterest().paise());
    }
}
