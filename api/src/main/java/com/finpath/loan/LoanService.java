package com.finpath.loan;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.finpath.dpdp.ConsentService;
import com.finpath.income.IncomeService;
import com.finpath.loan.LoanRepository.Draft;
import com.finpath.loan.LoanRepository.Row;
import com.finpath.rules.AmortisingLoan;
import com.finpath.rules.BulletLoan;
import com.finpath.rules.CycleCalendar;
import com.finpath.rules.EmiGuard;
import com.finpath.rules.EmiRollOff;
import com.finpath.rules.LoanDetail;
import com.finpath.rules.LoanType;
import com.finpath.rules.Money;
import com.finpath.rules.PayoffPlanner;
import com.finpath.rules.PrepaymentSimulator;
import com.finpath.rules.RuleConfig;
import com.finpath.rules.Statistics;
import com.finpath.rules.WaterfallAllocator;
import com.finpath.rules.SinkingFund;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Loans: validation and persistence. A stated EMI is stored verbatim and never recomputed; when none
 * is stated the instalment comes from the rules package (AmortisingLoan.emi, BulletLoan.monthlyInterest)
 * and is stored with emiDerived = true. This class does no money arithmetic and logs nothing.
 */
@Service
public class LoanService {

    static final String EMI = "emi";
    static final String BULLET = "bullet";
    static final String CARD_EMI = "card_emi";
    private static final Set<String> TYPES = Set.of(EMI, BULLET, CARD_EMI);
    private static final int MAX_LENDER = 60;
    private static final int RATE_SCALE = 10;

    /** Write payload. The owner is never part of it. Paise and counts must be JSON integers; rate is a decimal string. */
    public record LoanInput(String lender, String type,
            @JsonDeserialize(using = StrictInteger.class) Long principalOutstandingPaise, String rate,
            @JsonDeserialize(using = StrictInteger.class) Long emiPaise,
            @JsonDeserialize(using = StrictInteger.class) Long emisLeft, LocalDate bulletDueDate,
            @JsonDeserialize(using = StrictInteger.class) Long upfrontFeePaise,
            @JsonDeserialize(using = StrictInteger.class) Long dueDay) {
    }

    public record LoanView(UUID id, String lender, String type, long principalOutstandingPaise, String rate,
            Long emiPaise, boolean emiDerived, Integer emisLeft, LocalDate bulletDueDate, long upfrontFeePaise,
            Instant createdAt, Integer dueDay) {
    }

    static final ZoneId CYCLE_ZONE = ZoneId.of("Asia/Kolkata");
    static final String COMPUTED_EMI_LABEL = "Calculated from principal, rate and tenure (comparison only)";

    /** One remaining payment; paise throughout. */
    public record InstalmentView(int number, long openingPaise, long interestPaise, long principalPaise,
            long paymentPaise, long closingPaise) {
    }

    /** The cycle [start, endExclusive) of the final payment. */
    public record PayoffCycle(LocalDate start, LocalDate endExclusive) {
    }

    /** The rules-computed EMI, shown only next to a stated one and never used for cash flow. */
    public record ComputedEmiComparison(String label, long emiPaise) {
    }

    public record LoanDetailView(UUID id, String lender, String type, long emiUsedPaise, boolean emiDerived,
            ComputedEmiComparison computedEmiComparison, String effectiveAnnualRate, long interestRemainingPaise,
            long totalRemainingPayablePaise, int instalmentsRemaining, PayoffCycle payoffCycle, boolean closed,
            boolean pastDue, boolean balloonFinalPayment, List<InstalmentView> instalments, Integer dueDay) {
    }

    /** One loan in payoff order; payoffCycle is the 0-based cycle offset in which it closes. */
    public record PayoffStepView(UUID loanId, String lender, long outstandingPaise, String annualRate,
            long monthlyPaymentPaise, int payoffCycle, PayoffCycle payoffCycleDates) {
    }

    public record PayoffPlanView(String strategy, List<PayoffStepView> order, long totalInterestPaise,
            long totalPaidPaise, int cycles) {
    }

    /** One freed-EMI step; cycleId is the start date of the cycle in which the EMI no longer falls due. */
    public record RollOffStepView(LocalDate cycleId, LocalDate cycleEndExclusive, int cycleIndex,
            List<UUID> loanIds, long freedPaise, long cumulativeFreedPaise, long redirectedPaise,
            long cumulativeRedirectedPaise) {
    }

    public record RollOffView(String redirectPct, List<RollOffStepView> steps) {
    }

    /** FR-16 sinking-fund status for a bullet loan; paise throughout, status is BEHIND / PAST_DUE etc. */
    public record SinkingFundView(UUID loanId, int cyclesRemaining, long outstandingPaise, long setAsidePerCyclePaise,
            long finalCycleAmountPaise, long expectedSavedByNowPaise, long savedDeltaPaise, long catchUpPaise,
            String status, boolean behindSchedule) {
    }

    /** The inputs the simulation ran on, so the client can show them. */
    public record PrepaymentAssumptions(String mode, String annualRate, long principalOutstandingPaise,
            long currentEmiPaise, int tenureMonths, LocalDate firstInstalmentFrom, String note) {
    }

    public record PrepaymentView(UUID loanId, PrepaymentAssumptions assumptions, long newEmiPaise,
            int baselineInstalments, int newInstalments, int instalmentsSaved, long baselineInterestPaise,
            long newInterestPaise, long interestSavedPaise, PayoffCycle baselinePayoffCycle,
            PayoffCycle newPayoffCycle) {
    }

    static final String PREPAY_NOTE = "Schedule maths on the stated outstanding, rate and tenure; instalments are"
            + " counted from this cycle; no prepayment charges or fees are modelled.";

    /** FR-18 verdict; ratio is a decimal fraction string, null when there is no income. */
    public record GuardView(String ratio, String status, long headroomPaise, long totalEmiPaise,
            long monthlyIncomePaise) {
    }

    private final LoanRepository loans;
    private final ConsentService consents;
    private final IncomeService income;
    private final RuleConfig config;
    private final Clock clock;

    @Autowired
    public LoanService(LoanRepository loans, ConsentService consents, IncomeService income, RuleConfig config) {
        this(loans, consents, income, config, Clock.system(CYCLE_ZONE));
    }

    LoanService(LoanRepository loans, ConsentService consents, IncomeService income, RuleConfig config,
            Clock clock) {
        this.loans = loans;
        this.consents = consents;
        this.income = income;
        this.config = config;
        this.clock = clock;
    }

    /** The guard over the caller's current loans and current-cycle income (no new loan). */
    public GuardView guard(UUID userId) {
        return evaluate(userId, null);
    }

    private GuardView evaluate(UUID userId, Long extraEmiPaise) {
        List<Money> emis = new ArrayList<>();
        loans.list(userId).stream().filter(r -> r.principalOutstandingPaise() > 0)
                .forEach(r -> emis.add(Money.ofPaise(r.emiPaise() == null ? 0L : r.emiPaise())));
        if (extraEmiPaise != null) {
            emis.add(Money.ofPaise(extraEmiPaise));
        }
        Money total = EmiGuard.total(emis);
        Money monthlyIncome = Money.ofPaise(income.cycleTotal(userId).totalPaise());
        EmiGuard.Result g = EmiGuard.evaluate(total, monthlyIncome, config);
        return new GuardView(g.ratio() == null ? null : g.ratio().toPlainString(), g.status().name(),
                g.headroom().paise(), total.paise(), monthlyIncome.paise());
    }

    /** FR-13 detail. Every figure comes from LoanDetail.compute; the stated EMI drives cash flow. */
    public LoanDetailView detail(UUID userId, UUID id) {
        Row r = loans.find(userId, id).orElseThrow(LoanNotFoundException::new);
        int startDay = loans.cycleStartDay(userId).orElseThrow(LoanNotFoundException::new);
        LoanDetail.Result d;
        try {
            d = LoanDetail.compute(loanType(r.type()), Money.ofPaise(r.principalOutstandingPaise()), r.rate(),
                    r.emiDerived() || r.emiPaise() == null ? null : Money.ofPaise(r.emiPaise()), r.emisLeft(),
                    r.bulletDueDate(), Money.ofPaise(r.upfrontFeePaise()), LocalDate.now(clock), startDay);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new InvalidLoanException();
        }
        ComputedEmiComparison cmp = d.emiDerived() ? null
                : d.computedEmi().map(m -> new ComputedEmiComparison(COMPUTED_EMI_LABEL, m.paise())).orElse(null);
        PayoffCycle payoff = d.payoffCycle().map(LoanService::payoff).orElse(null);
        List<InstalmentView> rows = d.instalments().stream().map(i -> new InstalmentView(i.number(),
                i.opening().paise(), i.interest().paise(), i.principal().paise(), i.payment().paise(),
                i.closing().paise())).toList();
        return new LoanDetailView(r.id(), r.lender(), r.type(), d.emiUsed().paise(), d.emiDerived(), cmp,
                d.effectiveAnnualRate().toPlainString(), d.interestRemaining().paise(),
                d.totalRemainingPayable().paise(), d.instalmentsRemaining(), payoff, d.closed(), d.pastDue(),
                d.balloonFinalPayment(), rows, r.dueDay());
    }

    /** FR-14 plan over the caller's open loans. The strategy is required: anything else is 400, never a default. */
    public PayoffPlanView payoffPlan(UUID userId, String strategyName) {
        return payoffPlan(userId, strategy(strategyName), LocalDate.now(clock));
    }

    /**
     * Cycle in which the last open debt closes under the avalanche plan (Statistics.debtFreeCycle), or null
     * when there is no open debt or the plan cannot be simulated (a payment that never covers interest). Null
     * means "no date to show", never an error, so the dashboard still renders.
     */
    public PayoffCycle debtFree(UUID userId, LocalDate today) {
        if (loans.list(userId).stream().noneMatch(r -> r.principalOutstandingPaise() > 0)) {
            return null;
        }
        PayoffPlanView plan;
        try {
            plan = payoffPlan(userId, PayoffPlanner.Strategy.AVALANCHE, today);
        } catch (InvalidLoanException e) {
            return null;
        }
        java.util.OptionalInt last = Statistics.debtFreeCycle(plan.order().stream().map(PayoffStepView::payoffCycle).toList());
        return last.isEmpty() ? null : plan.order().stream().filter(st -> st.payoffCycle() == last.getAsInt())
                .findFirst().map(PayoffStepView::payoffCycleDates).orElse(null);
    }

    private PayoffPlanView payoffPlan(UUID userId, PayoffPlanner.Strategy strategy, LocalDate today) {
        int startDay = loans.cycleStartDay(userId).orElseThrow(LoanNotFoundException::new);
        List<Row> rows = loans.list(userId);
        List<PayoffPlanner.DebtEntry> debts = rows.stream().map(r -> debtEntry(r, today, startDay)).toList();
        PayoffPlanner.Plan plan;
        try {
            plan = PayoffPlanner.plan(debts, strategy);
        } catch (IllegalArgumentException | ArithmeticException | IllegalStateException e) {
            throw new InvalidLoanException();
        }
        java.util.Map<String, Row> byId = new java.util.HashMap<>();
        rows.forEach(r -> byId.put(r.id().toString(), r));
        java.util.Map<String, PayoffPlanner.DebtEntry> entries = new java.util.HashMap<>();
        debts.forEach(e -> entries.put(e.id(), e));
        List<PayoffStepView> steps = plan.order().stream().map(id -> {
            Row r = byId.get(id);
            PayoffPlanner.DebtEntry e = entries.get(id);
            int offset = plan.payoffCycle().get(id);
            CycleCalendar.Cycle c = CycleCalendar.cycleFor(today, startDay);
            for (int i = 0; i < offset; i++) {
                c = CycleCalendar.next(c, startDay);
            }
            return new PayoffStepView(r.id(), r.lender(), e.outstanding().paise(),
                    e.annualRate().stripTrailingZeros().toPlainString(), e.monthlyPayment().paise(),
                    offset, payoff(c));
        }).toList();
        return new PayoffPlanView(strategy.name().toLowerCase(java.util.Locale.ROOT), steps,
                plan.totalInterest().paise(), plan.totalPaid().paise(), plan.cycles());
    }

    /** FR-15 timeline over the caller's open loans; every figure comes from EmiRollOff. */
    public RollOffView rollOff(UUID userId) {
        int startDay = loans.cycleStartDay(userId).orElseThrow(LoanNotFoundException::new);
        LocalDate today = LocalDate.now(clock);
        List<EmiRollOff.Debt> debts = loans.list(userId).stream().map(r -> {
            try {
                LoanDetail.Result d = LoanDetail.compute(loanType(r.type()),
                        Money.ofPaise(r.principalOutstandingPaise()), r.rate(),
                        r.emiDerived() || r.emiPaise() == null ? null : Money.ofPaise(r.emiPaise()), r.emisLeft(),
                        r.bulletDueDate(), Money.ofPaise(r.upfrontFeePaise()), today, startDay);
                return new EmiRollOff.Debt(r.id().toString(), d.emiUsed(), d.instalmentsRemaining());
            } catch (IllegalArgumentException | ArithmeticException e) {
                throw new InvalidLoanException();
            }
        }).toList();
        List<EmiRollOff.Step> steps = EmiRollOff.timeline(debts, today, startDay);
        return new RollOffView(RuleConfig.DEFAULT_EMI_REDIRECT.stripTrailingZeros().toPlainString(),
                steps.stream().map(s -> new RollOffStepView(s.cycleId(), s.cycle().endExclusive(), s.cycleIndex(),
                        s.debtIds().stream().map(UUID::fromString).toList(), s.freed().paise(),
                        s.cumulativeFreed().paise(), s.redirected().paise(), s.cumulativeRedirected().paise()))
                        .toList());
    }

    private static PayoffPlanner.DebtEntry debtEntry(Row r, LocalDate today, int startDay) {
        try {
            LoanDetail.Result d = LoanDetail.compute(loanType(r.type()), Money.ofPaise(r.principalOutstandingPaise()),
                    r.rate(), r.emiDerived() || r.emiPaise() == null ? null : Money.ofPaise(r.emiPaise()),
                    r.emisLeft(), r.bulletDueDate(), Money.ofPaise(r.upfrontFeePaise()), today, startDay);
            return new PayoffPlanner.DebtEntry(r.id().toString(), d.effectiveAnnualRate(),
                    Money.ofPaise(r.principalOutstandingPaise()), d.emiUsed());
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new InvalidLoanException();
        }
    }

    private static PayoffPlanner.Strategy strategy(String raw) {
        if ("avalanche".equals(raw)) {
            return PayoffPlanner.Strategy.AVALANCHE;
        }
        if ("snowball".equals(raw)) {
            return PayoffPlanner.Strategy.SNOWBALL;
        }
        throw new InvalidLoanException();
    }

    /**
     * Open debts as waterfall targets (FR-22). A bullet loan absorbs its sinking-fund deposit (the whole
     * balance once past due); any other loan absorbs its outstanding balance.
     */
    public List<WaterfallAllocator.DebtTarget> debtTargets(UUID userId) {
        List<WaterfallAllocator.DebtTarget> out = new ArrayList<>();
        for (Row r : loans.list(userId)) {
            if (r.principalOutstandingPaise() <= 0) {
                continue;
            }
            boolean bullet = BULLET.equals(r.type());
            long absorbable = r.principalOutstandingPaise();
            if (bullet) {
                try {
                    absorbable = sinkingFund(userId, r.id(), 0L, null).setAsidePerCyclePaise();
                } catch (InvalidLoanException e) {
                    // no usable due date or already past it: fall back to the outstanding balance
                }
            }
            out.add(new WaterfallAllocator.DebtTarget(r.id().toString(), r.rate(), bullet, Money.ofPaise(absorbable)));
        }
        return out;
    }

    /**
     * FR-16. The amount already set aside is supplied by the caller (nothing is stored); the fund starts
     * on fundStart, defaulting to the day the loan was recorded. Only bullet loans have a sinking fund.
     */
    public SinkingFundView sinkingFund(UUID userId, UUID id, Long savedPaise, LocalDate fundStart) {
        Row r = loans.find(userId, id).orElseThrow(LoanNotFoundException::new);
        if (!BULLET.equals(r.type()) || savedPaise == null || savedPaise < 0 || r.bulletDueDate() == null) {
            throw new InvalidLoanException();
        }
        int startDay = loans.cycleStartDay(userId).orElseThrow(LoanNotFoundException::new);
        LocalDate start = fundStart != null ? fundStart : r.createdAt().atZone(CYCLE_ZONE).toLocalDate();
        SinkingFund.Result f;
        try {
            f = SinkingFund.evaluate(Money.ofPaise(r.principalOutstandingPaise()), Money.ofPaise(savedPaise), start,
                    r.bulletDueDate(), LocalDate.now(clock), startDay);
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new InvalidLoanException();
        }
        boolean behind = f.status() == SinkingFund.Status.BEHIND || f.status() == SinkingFund.Status.PAST_DUE;
        return new SinkingFundView(r.id(), f.cyclesRemaining(), f.outstanding().paise(), f.setAsidePerCycle().paise(),
                f.finalCycleAmount().paise(), f.expectedSavedByNow().paise(), f.savedDelta().paise(),
                f.catchUp().paise(), f.status().name(), behind);
    }

    /**
     * FR-17 what-if for an amortising (emi) loan. mode is one of lump_sum (lumpPaise, optional afterInstalment),
     * increase_emi (newEmiPaise) or close_in (closeMonths); anything else is 400.
     */
    public PrepaymentView prepayment(UUID userId, UUID id, String mode, Long lumpPaise, Integer afterInstalment,
            Long newEmiPaise, Integer closeMonths) {
        Row r = loans.find(userId, id).orElseThrow(LoanNotFoundException::new);
        int startDay = loans.cycleStartDay(userId).orElseThrow(LoanNotFoundException::new);
        if (!EMI.equals(r.type()) || mode == null || r.emisLeft() == null || r.emiPaise() == null
                || r.emisLeft() <= 0 || r.principalOutstandingPaise() <= 0) {
            throw new InvalidLoanException();
        }
        Money principal = Money.ofPaise(r.principalOutstandingPaise());
        Money emi = Money.ofPaise(r.emiPaise());
        int tenure = r.emisLeft();
        LocalDate today = LocalDate.now(clock);
        PrepaymentSimulator.Outcome o;
        try {
            o = switch (mode) {
                case "lump_sum" -> {
                    if (lumpPaise == null) {
                        throw new InvalidLoanException();
                    }
                    yield PrepaymentSimulator.lumpSum(principal, r.rate(), emi, tenure,
                            afterInstalment == null ? 0 : afterInstalment, Money.ofPaise(lumpPaise));
                }
                case "increase_emi" -> {
                    if (newEmiPaise == null) {
                        throw new InvalidLoanException();
                    }
                    yield PrepaymentSimulator.increaseEmi(principal, r.rate(), emi, tenure,
                            Money.ofPaise(newEmiPaise));
                }
                case "close_in" -> {
                    if (closeMonths == null) {
                        throw new InvalidLoanException();
                    }
                    yield PrepaymentSimulator.closeIn(principal, r.rate(), tenure, closeMonths);
                }
                default -> throw new InvalidLoanException();
            };
        } catch (IllegalArgumentException | ArithmeticException | IllegalStateException e) {
            throw new InvalidLoanException();
        }
        return new PrepaymentView(r.id(),
                new PrepaymentAssumptions(mode, r.rate().stripTrailingZeros().toPlainString(),
                        r.principalOutstandingPaise(), r.emiPaise(), tenure, today, PREPAY_NOTE),
                o.newEmi().paise(), o.baselineInstalments(), o.newInstalments(), o.instalmentsSaved(),
                o.baselineInterest().paise(), o.newInterest().paise(), o.interestSaved().paise(),
                o.baselinePayoffCycle(today, startDay).map(LoanService::payoff).orElse(null),
                o.newPayoffCycle(today, startDay).map(LoanService::payoff).orElse(null));
    }

    private static PayoffCycle payoff(CycleCalendar.Cycle c) {
        return new PayoffCycle(c.start(), c.endExclusive());
    }

    private static LoanType loanType(String dbValue) {
        return switch (dbValue) {
            case EMI -> LoanType.EMI;
            case BULLET -> LoanType.BULLET;
            case CARD_EMI -> LoanType.CARD_EMI;
            default -> throw new InvalidLoanException();
        };
    }

    /**
     * Creates a loan. The debt-trap guard runs on the existing EMIs plus this one: BLOCK refuses the
     * write (409) unless the client explicitly confirms.
     */
    public LoanView create(UUID userId, LoanInput in, boolean confirmBlock) {
        Draft d = draft(in, LocalDate.now(clock));
        consents.require(userId);
        GuardView g = evaluate(userId, d.emiPaise());
        if (!confirmBlock && EmiGuard.Status.BLOCK.name().equals(g.status())) {
            throw new DebtTrapBlockedException(g);
        }
        return view(loans.insert(userId, d));
    }

    public LoanView replace(UUID userId, UUID id, LoanInput in) {
        Draft d = draft(in, LocalDate.now(clock));
        consents.require(userId);
        return view(loans.update(userId, id, d).orElseThrow(LoanNotFoundException::new));
    }

    public LoanView get(UUID userId, UUID id) {
        return view(loans.find(userId, id).orElseThrow(LoanNotFoundException::new));
    }

    public List<LoanView> list(UUID userId) {
        return loans.list(userId).stream().map(LoanService::view).toList();
    }

    public void delete(UUID userId, UUID id) {
        consents.require(userId);
        if (!loans.delete(userId, id)) {
            throw new LoanNotFoundException();
        }
    }

    /** Validates and resolves the instalment; throws InvalidLoanException before any write. */
    private static Draft draft(LoanInput in, LocalDate today) {
        if (in == null || in.type() == null || !TYPES.contains(in.type()) || in.lender() == null
                || in.principalOutstandingPaise() == null || in.principalOutstandingPaise() < 0) {
            throw new InvalidLoanException();
        }
        String type = in.type();
        String lender = in.lender().strip();
        if (lender.isEmpty() || lender.length() > MAX_LENDER) {
            throw new InvalidLoanException();
        }
        BigDecimal rate = rate(in.rate());
        boolean bullet = BULLET.equals(type);
        if (bullet != (in.bulletDueDate() != null) || bullet != (in.emisLeft() == null)) {
            throw new InvalidLoanException();
        }
        Integer emisLeft = null;
        if (!bullet) {
            if (!LoanDetail.tenureWithinLimit(in.emisLeft())) {
                throw new InvalidLoanException();
            }
            emisLeft = in.emisLeft().intValue();
        }
        if (bullet && !LoanDetail.dueWithinHorizon(in.bulletDueDate(), today)) {
            throw new InvalidLoanException();
        }
        long fee = in.upfrontFeePaise() == null ? 0L : in.upfrontFeePaise();
        if (fee < 0 || (fee > 0 && (!CARD_EMI.equals(type) || fee >= in.principalOutstandingPaise()))) {
            throw new InvalidLoanException();
        }
        Integer dueDay = null;
        if (in.dueDay() != null) {
            if (in.dueDay() < 1 || in.dueDay() > 31) {
                throw new InvalidLoanException();
            }
            dueDay = in.dueDay().intValue();
        }
        Long emi = in.emiPaise();
        boolean derived = false;
        if (emi != null) {
            if (emi <= 0) {
                throw new InvalidLoanException();
            }
        } else {
            emi = derive(type, in.principalOutstandingPaise(), rate, emisLeft);
            derived = true;
        }
        return new Draft(lender, type, in.principalOutstandingPaise(), rate, emi, derived, emisLeft,
                in.bulletDueDate(), fee, dueDay);
    }

    /** Rules-engine instalment for an unstated EMI; card_emi has none (the lender's figure is required). */
    private static long derive(String type, long principalPaise, BigDecimal rate, Integer emisLeft) {
        try {
            Money principal = Money.ofPaise(principalPaise);
            Money m = switch (type) {
                case EMI -> AmortisingLoan.emi(principal, rate, emisLeft);
                case BULLET -> BulletLoan.monthlyInterest(principal, rate);
                default -> throw new InvalidLoanException();
            };
            if (m.paise() <= 0) {
                throw new InvalidLoanException();
            }
            return m.paise();
        } catch (IllegalArgumentException | ArithmeticException e) {
            throw new InvalidLoanException();
        }
    }

    /** Annual fraction as a decimal string, at most 10 decimal places, in [0, 1). */
    private static BigDecimal rate(String raw) {
        if (raw == null) {
            throw new InvalidLoanException();
        }
        BigDecimal r;
        try {
            r = new BigDecimal(raw.strip());
        } catch (NumberFormatException e) {
            throw new InvalidLoanException();
        }
        if (r.signum() < 0 || r.compareTo(BigDecimal.ONE) >= 0 || r.stripTrailingZeros().scale() > RATE_SCALE) {
            throw new InvalidLoanException();
        }
        return r;
    }

    private static LoanView view(Row r) {
        return new LoanView(r.id(), r.lender(), r.type(), r.principalOutstandingPaise(),
                r.rate().stripTrailingZeros().toPlainString(), r.emiPaise(), r.emiDerived(), r.emisLeft(),
                r.bulletDueDate(), r.upfrontFeePaise(), r.createdAt(), r.dueDay());
    }
}
