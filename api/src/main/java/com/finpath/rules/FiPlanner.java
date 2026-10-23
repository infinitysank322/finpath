package com.finpath.rules;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * Financial-independence planner (FR-21). Pure: combines {@link FiCorpus} and {@link StepUpSip} over a
 * low / base / high return band. Rates come from {@link RuleConfig}: low is the upper debt return,
 * high is the equity return and base is their midpoint. An opening balance (for example EPF, entered
 * by the user) compounds monthly at the band rate alongside the SIP.
 */
public final class FiPlanner {

    public enum Band { LOW, BASE, HIGH }

    private static final int MONTHS_PER_YEAR = 12;
    private static final BigDecimal MONTHS = BigDecimal.valueOf(MONTHS_PER_YEAR);
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    /** requiredFirstMonthly is the step-up SIP start needed to hit the net corpus at the horizon; fiYears is empty if never. */
    public record BandResult(Band band, BigDecimal annualReturn, Money requiredFirstMonthly,
                             Money projectedSavingsAtHorizon, OptionalInt fiYears) {
    }

    public record Plan(FiCorpus.Result corpus, int yearsToFi, List<BandResult> bands) {
    }

    private FiPlanner() {
    }

    public static BigDecimal bandRate(Band band, RuleConfig config) {
        Objects.requireNonNull(band, "band");
        Objects.requireNonNull(config, "config");
        return switch (band) {
            case LOW -> config.debtReturnHigh();
            case HIGH -> config.equityReturn();
            case BASE -> config.debtReturnHigh().add(config.equityReturn()).divide(TWO, MathPolicy.CALC);
        };
    }

    /** {@code amount} compounded monthly at {@code annualReturn / 12} for {@code months}. */
    public static Money lumpFuture(Money amount, BigDecimal annualReturn, int months) {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(annualReturn, "annualReturn");
        if (amount.isNegative() || months < 0) {
            throw new IllegalArgumentException("amount and months must not be negative");
        }
        BigDecimal growth = BigDecimal.ONE.add(annualReturn.divide(MONTHS, MathPolicy.CALC));
        return amount.times(growth.pow(months, MathPolicy.CALC));
    }

    /** Opening balance plus SIP, each compounded for {@code years}. */
    public static Money savingsAfter(Money opening, Money firstMonthly, BigDecimal annualReturn, BigDecimal stepUp,
                                     int years) {
        int months = Math.multiplyExact(years, MONTHS_PER_YEAR);
        Money lump = lumpFuture(opening, annualReturn, months);
        if (months == 0) {
            return lump;
        }
        return lump.plus(StepUpSip.futureValue(firstMonthly, annualReturn, stepUp, months));
    }

    /**
     * @param yearsToFi target horizon, at least 1, sizes the corpus and the required SIP
     * @param maxYears  how far the FI-date solve looks, at least {@code yearsToFi}
     */
    public static Plan plan(Money todayMonthlyExpense, Money todayMonthlyPassive, Money opening,
                            Money firstMonthlySip, BigDecimal stepUp, int yearsToFi, int maxYears,
                            RuleConfig config) {
        Objects.requireNonNull(opening, "opening");
        Objects.requireNonNull(firstMonthlySip, "firstMonthlySip");
        if (yearsToFi < 1 || maxYears < yearsToFi) {
            throw new IllegalArgumentException("yearsToFi must be at least 1 and no more than maxYears");
        }
        FiCorpus.Result corpus = FiCorpus.corpus(todayMonthlyExpense, todayMonthlyPassive, yearsToFi, config);
        int months = Math.multiplyExact(yearsToFi, MONTHS_PER_YEAR);
        List<BandResult> bands = java.util.Arrays.stream(Band.values()).map(b -> {
            BigDecimal r = bandRate(b, config);
            Money have = lumpFuture(opening, r, months);
            Money gap = Money.ofPaise(Math.max(0L, corpus.netCorpus().paise() - have.paise()));
            Money required = StepUpSip.requiredFirstMonthly(gap, r, stepUp, months);
            Money projected = savingsAfter(opening, firstMonthlySip, r, stepUp, yearsToFi);
            OptionalInt fi = FiCorpus.fiYears(todayMonthlyExpense, todayMonthlyPassive, maxYears,
                    y -> savingsAfter(opening, firstMonthlySip, r, stepUp, y), config);
            return new BandResult(b, r, required, projected, fi);
        }).toList();
        return new Plan(corpus, yearsToFi, bands);
    }
}
