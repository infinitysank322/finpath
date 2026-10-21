package com.finpath.rules;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * EMI-to-income ratio and debt-trap guard (FR-18). Pure and deterministic. Thresholds come only
 * from {@link RuleConfig}.
 *
 * <p>Comparison is exact: the status is decided by cross-multiplying paise
 * ({@code emi > threshold x income}) with no rounding before the comparison, so a ratio of
 * 40.01% is {@link Status#WARN} even though it would display as 40.0%. The reported
 * {@link Result#ratio()} is rounded (HALF_UP, {@link MathPolicy#RATE_SCALE}) for display only.
 *
 * <p>Boundaries: ratio at or below the warn threshold is OK; above warn up to and including the
 * block threshold is WARN; strictly above the block threshold is BLOCK. A positive EMI with no
 * income (zero or negative) is BLOCK with an empty ratio; zero EMI is always OK.
 *
 * <p>Headroom is the extra monthly EMI that can be added before the ratio would go above the
 * warn threshold, i.e. {@code floor(warn x income) - emi}, floored at zero. It is zero when
 * already over warn or when there is no income.
 */
public final class DebtTrapGuard {

    private DebtTrapGuard() {
    }

    public enum Status { OK, WARN, BLOCK }

    /**
     * @param ratio EMI divided by income as a fraction, rounded for display; empty when income
     *              is zero or negative and so no ratio exists
     * @param status guard decision, made on the exact (unrounded) ratio
     * @param headroom extra EMI affordable before the warn threshold, never negative
     */
    public record Result(Optional<BigDecimal> ratio, Status status, Money headroom) {
    }

    /**
     * @param monthlyIncome in-hand monthly income
     * @param totalEmi total monthly EMI including any proposed new one
     * @throws IllegalArgumentException if the EMI is negative
     */
    public static Result evaluate(Money monthlyIncome, Money totalEmi, RuleConfig config) {
        Objects.requireNonNull(monthlyIncome, "monthlyIncome");
        Objects.requireNonNull(totalEmi, "totalEmi");
        Objects.requireNonNull(config, "config");
        if (totalEmi.isNegative()) {
            throw new IllegalArgumentException("totalEmi must not be negative");
        }
        if (totalEmi.paise() == 0L) {
            return new Result(monthlyIncome.paise() > 0L ? Optional.of(BigDecimal.ZERO) : Optional.empty(),
                    Status.OK, headroom(monthlyIncome, totalEmi, config));
        }
        if (monthlyIncome.paise() <= 0L) {
            return new Result(Optional.empty(), Status.BLOCK, Money.ZERO);
        }
        BigDecimal income = BigDecimal.valueOf(monthlyIncome.paise());
        BigDecimal emi = BigDecimal.valueOf(totalEmi.paise());
        BigDecimal ratio = emi.divide(income, MathPolicy.RATE_SCALE, MathPolicy.MONEY_ROUNDING);
        Status status;
        if (emi.compareTo(income.multiply(config.emiToIncomeBlock())) > 0) {
            status = Status.BLOCK;
        } else if (emi.compareTo(income.multiply(config.emiToIncomeWarn())) > 0) {
            status = Status.WARN;
        } else {
            status = Status.OK;
        }
        return new Result(Optional.of(ratio), status, headroom(monthlyIncome, totalEmi, config));
    }

    private static Money headroom(Money income, Money emi, RuleConfig config) {
        if (income.paise() <= 0L) {
            return Money.ZERO;
        }
        long allowed = BigDecimal.valueOf(income.paise()).multiply(config.emiToIncomeWarn())
                .setScale(0, java.math.RoundingMode.FLOOR).longValueExact();
        return Money.ofPaise(Math.max(0L, allowed - emi.paise()));
    }
}
