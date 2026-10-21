package com.finpath.rules;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A loan carrying its type, so a rule that only understands one shape can refuse the others
 * instead of returning a plausible but wrong number. Plain values only; no persistence concerns.
 */
public sealed interface Loan permits Loan.Emi, Loan.Bullet, Loan.CardEmi {

    LoanType type();

    Money principal();

    /** Amortising loan at an annual fractional rate (0.08 = 8%). */
    record Emi(Money principal, BigDecimal annualRate) implements Loan {

        public Emi {
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(annualRate, "annualRate");
        }

        @Override
        public LoanType type() {
            return LoanType.EMI;
        }
    }

    /** Interest-only loan: {@code monthlyInterest} each cycle, principal due on {@code dueDate}. */
    record Bullet(Money principal, Money monthlyInterest, LocalDate dueDate) implements Loan {

        public Bullet {
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(monthlyInterest, "monthlyInterest");
            Objects.requireNonNull(dueDate, "dueDate");
            if (principal.isNegative() || monthlyInterest.isNegative()) {
                throw new IllegalArgumentException("principal and monthlyInterest must not be negative");
            }
        }

        @Override
        public LoanType type() {
            return LoanType.BULLET;
        }
    }

    /**
     * Card EMI: {@code principal} is the purchase amount converted, {@code upfrontFee} is paid at
     * conversion (processing fee incl. tax), then {@code instalment} for {@code tenureMonths}.
     */
    record CardEmi(Money principal, Money upfrontFee, Money instalment, int tenureMonths) implements Loan {

        public CardEmi {
            Objects.requireNonNull(principal, "principal");
            Objects.requireNonNull(upfrontFee, "upfrontFee");
            Objects.requireNonNull(instalment, "instalment");
            if (principal.isNegative() || upfrontFee.isNegative() || instalment.isNegative()) {
                throw new IllegalArgumentException("amounts must not be negative");
            }
            if (tenureMonths <= 0) {
                throw new IllegalArgumentException("tenure must be positive: " + tenureMonths);
            }
        }

        @Override
        public LoanType type() {
            return LoanType.CARD_EMI;
        }
    }
}
