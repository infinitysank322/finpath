package com.finpath.fi;

import com.finpath.rules.FiCorpus;
import com.finpath.rules.FiPlanner;
import com.finpath.rules.Money;
import com.finpath.rules.RuleConfig;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * FR-21 FI planner. A stateless calculator over request inputs: nothing is stored or logged, and every
 * figure comes from {@link FiPlanner}. The opening balance (OQ-1: EPF and similar) is user-entered and
 * defaults to zero; it is never derived.
 */
@Service
public class FiPlannerService {

    static final int MAX_YEARS = 60;
    static final String DISCLAIMER = "Educational projection, not investment advice. Returns are assumptions, "
            + "not guarantees.";
    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");

    public record Assumptions(String inflation, String equityReturn, String debtReturnLow, String debtReturnHigh,
            int fiMultiplier, String stepUp) {
    }

    /** fiYears / fiDate are null when savings never reach the corpus within the solve horizon. */
    public record BandView(String band, String annualReturn, long requiredFirstMonthlyPaise,
            long projectedSavingsAtHorizonPaise, Integer fiYears, LocalDate fiDate) {
    }

    public record FiPlanView(int yearsToFi, long inflatedMonthlyExpensePaise, long inflatedMonthlyPassivePaise,
            long grossCorpusPaise, long netCorpusPaise, long openingBalancePaise, long monthlySipPaise,
            List<BandView> bands, Assumptions assumptions, String disclaimer) {
    }

    private final RuleConfig config;
    private final Clock clock;

    @Autowired
    public FiPlannerService(RuleConfig config) {
        this(config, Clock.system(ZONE));
    }

    FiPlannerService(RuleConfig config, Clock clock) {
        this.config = config;
        this.clock = clock;
    }

    public FiPlanView plan(Long expensePaise, Long passivePaise, Long openingPaise, Long sipPaise, String stepUpText,
            Integer yearsToFi) {
        if (expensePaise == null || expensePaise < 0 || yearsToFi == null || yearsToFi < 1
                || yearsToFi > MAX_YEARS) {
            throw new InvalidFiException();
        }
        long passive = passivePaise == null ? 0L : passivePaise;
        long opening = openingPaise == null ? 0L : openingPaise;
        long sip = sipPaise == null ? 0L : sipPaise;
        if (passive < 0 || opening < 0 || sip < 0) {
            throw new InvalidFiException();
        }
        BigDecimal stepUp = BigDecimal.ZERO;
        if (stepUpText != null) {
            try {
                stepUp = new BigDecimal(stepUpText);
            } catch (NumberFormatException e) {
                throw new InvalidFiException();
            }
            if (stepUp.signum() < 0 || stepUp.compareTo(BigDecimal.ONE) > 0) {
                throw new InvalidFiException();
            }
        }
        FiPlanner.Plan p;
        try {
            p = FiPlanner.plan(Money.ofPaise(expensePaise), Money.ofPaise(passive), Money.ofPaise(opening),
                    Money.ofPaise(sip), stepUp, yearsToFi, MAX_YEARS, config);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new InvalidFiException();
        }
        LocalDate today = LocalDate.now(clock);
        FiCorpus.Result c = p.corpus();
        List<BandView> bands = p.bands().stream().map(b -> new BandView(b.band().name(),
                b.annualReturn().toPlainString(), b.requiredFirstMonthly().paise(),
                b.projectedSavingsAtHorizon().paise(), b.fiYears().isPresent() ? b.fiYears().getAsInt() : null,
                b.fiYears().isPresent() ? today.plusYears(b.fiYears().getAsInt()) : null)).toList();
        return new FiPlanView(yearsToFi, c.inflatedMonthlyExpense().paise(), c.inflatedMonthlyPassive().paise(),
                c.grossCorpus().paise(), c.netCorpus().paise(), opening, sip, bands,
                new Assumptions(config.inflation().toPlainString(), config.equityReturn().toPlainString(),
                        config.debtReturnLow().toPlainString(), config.debtReturnHigh().toPlainString(),
                        config.fiMultiplier(), stepUp.toPlainString()), DISCLAIMER);
    }
}
