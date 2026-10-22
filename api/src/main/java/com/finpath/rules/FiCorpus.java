package com.finpath.rules;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.function.IntFunction;

/**
 * Financial-independence corpus (FR-21). Pure: today's monthly expense is inflated to the FI age,
 * passive income (inflated the same way) is netted off, and the yearly remainder is multiplied by
 * {@link RuleConfig#fiMultiplier()}. Inflation and the multiplier come from the config only.
 */
public final class FiCorpus {

    private static final int MONTHS_PER_YEAR = 12;

    private FiCorpus() {
    }

    /** Amounts at the FI age; {@code netCorpus} is never negative. */
    public record Result(Money inflatedMonthlyExpense, Money inflatedMonthlyPassive, Money grossCorpus,
                         Money netCorpus) {
    }

    /** {@code amount} grown by the configured inflation for a whole number of years. */
    public static Money inflate(Money amount, int years, RuleConfig config) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(config, "config");
        if (amount.isNegative()) {
            throw new IllegalArgumentException("amount must not be negative");
        }
        if (years < 0) {
            throw new IllegalArgumentException("years must not be negative");
        }
        BigDecimal factor = BigDecimal.ONE.add(config.inflation()).pow(years, MathPolicy.CALC);
        return amount.times(factor);
    }

    public static Result corpus(Money todayMonthlyExpense, Money todayMonthlyPassive, int yearsToFi,
                                RuleConfig config) {
        Objects.requireNonNull(todayMonthlyPassive, "todayMonthlyPassive");
        Money expense = inflate(todayMonthlyExpense, yearsToFi, config);
        Money passive = inflate(todayMonthlyPassive, yearsToFi, config);
        Money gross = yearly(expense, config);
        Money netMonthly = Money.ofPaise(Math.max(0L, expense.paise() - passive.paise()));
        return new Result(expense, passive, gross, yearly(netMonthly, config));
    }

    private static Money yearly(Money monthly, RuleConfig config) {
        return Money.ofPaise(Math.multiplyExact(Math.multiplyExact(monthly.paise(), (long) MONTHS_PER_YEAR),
                (long) config.fiMultiplier()));
    }

    /**
     * Earliest whole number of years from now (0..maxYears) at which the projected savings reach the
     * net corpus needed at that point; empty if never within the horizon. The projection is supplied
     * by the caller (see the step-up SIP rule) so the solve stays independent of the growth model.
     */
    public static OptionalInt fiYears(Money todayMonthlyExpense, Money todayMonthlyPassive, int maxYears,
                                      IntFunction<Money> projectedSavings, RuleConfig config) {
        Objects.requireNonNull(projectedSavings, "projectedSavings");
        if (maxYears < 0) {
            throw new IllegalArgumentException("maxYears must not be negative");
        }
        for (int y = 0; y <= maxYears; y++) {
            Money needed = corpus(todayMonthlyExpense, todayMonthlyPassive, y, config).netCorpus();
            if (projectedSavings.apply(y).paise() >= needed.paise()) {
                return OptionalInt.of(y);
            }
        }
        return OptionalInt.empty();
    }
}
