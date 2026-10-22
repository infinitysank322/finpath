package com.finpath.rules;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Objects;

/**
 * EMI-to-income ratio and debt-trap guard (FR-18). Pure and deterministic. Thresholds come from
 * {@link RuleConfig}; the status comparison is exact (EMI against income x threshold, no rounded
 * ratio), so exactly the warn threshold is OK and anything above it is WARN. The same holds at
 * the block threshold: exactly at it is still WARN, above it is BLOCK.
 */
public final class EmiGuard {

    private EmiGuard() {
    }

    /** Guard verdict, ordered by severity. */
    public enum Status { OK, WARN, BLOCK }

    /**
     * Result of the guard. {@code ratio} is null when income is zero. {@code headroom} is the
     * extra monthly EMI that can be taken on before the block threshold is exceeded, rounded
     * DOWN to whole paise; zero once the block threshold is reached or passed.
     */
    public record Result(BigDecimal ratio, Status status, Money headroom) {
    }

    /** Sum of monthly instalments; the numerator the guard is evaluated on. */
    public static Money total(Collection<Money> emis) {
        Objects.requireNonNull(emis, "emis");
        Money sum = Money.ZERO;
        for (Money m : emis) {
            sum = sum.plus(Objects.requireNonNull(m, "emi"));
        }
        return sum;
    }

    /** Evaluate total monthly EMIs against monthly income. */
    public static Result evaluate(Money totalEmi, Money monthlyIncome, RuleConfig config) {
        Objects.requireNonNull(totalEmi, "totalEmi");
        Objects.requireNonNull(monthlyIncome, "monthlyIncome");
        Objects.requireNonNull(config, "config");
        if (totalEmi.isNegative()) {
            throw new IllegalArgumentException("totalEmi must not be negative");
        }
        if (monthlyIncome.isNegative()) {
            throw new IllegalArgumentException("monthlyIncome must not be negative");
        }
        if (monthlyIncome.paise() == 0) {
            return new Result(null, totalEmi.paise() > 0 ? Status.BLOCK : Status.OK, Money.ZERO);
        }
        BigDecimal emi = BigDecimal.valueOf(totalEmi.paise());
        BigDecimal income = BigDecimal.valueOf(monthlyIncome.paise());
        BigDecimal ratio = emi.divide(income, MathPolicy.RATE_SCALE, MathPolicy.MONEY_ROUNDING);

        Status status;
        if (emi.compareTo(income.multiply(config.emiToIncomeBlock())) > 0) {
            status = Status.BLOCK;
        } else if (emi.compareTo(income.multiply(config.emiToIncomeWarn())) > 0) {
            status = Status.WARN;
        } else {
            status = Status.OK;
        }

        BigDecimal room = income.multiply(config.emiToIncomeBlock()).subtract(emi);
        long headroom = room.signum() <= 0 ? 0 : room.setScale(0, RoundingMode.DOWN).longValueExact();
        return new Result(ratio, status, Money.ofPaise(headroom));
    }
}
