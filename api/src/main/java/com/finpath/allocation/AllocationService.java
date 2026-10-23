package com.finpath.allocation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpath.allocation.AllocationRepository.Stored;
import com.finpath.dpdp.ConsentService;
import com.finpath.goal.EmergencyFundService;
import com.finpath.goal.GoalService;
import com.finpath.income.IncomeService;
import com.finpath.loan.LoanService;
import com.finpath.plan.PlanCycleService;
import com.finpath.recurring.RecurringService;
import com.finpath.rules.BudgetRules;
import com.finpath.rules.Money;
import com.finpath.rules.RuleConfig;
import com.finpath.rules.WaterfallAllocator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * FR-22 allocation run. Gathers income, mandatory outflows, debts and goals, hands them to
 * {@link WaterfallAllocator} and stores the result on the open plan_cycle row. Mandatory outflows are
 * the active EMIs plus the current cycle's recurring instalments (rent, insurance and other fixed
 * bills). The step-7 split is a request value (OQ-3), defaulting to {@link RuleConfig#DEFAULT_PREPAY_SHARE}.
 * Re-running overwrites the row with the same result for the same inputs. No arithmetic and no logging here.
 */
@Service
public class AllocationService {

    public record Line(String step, String ref, long amountPaise) {
    }

    /** shortfallPaise is non-zero only when mandatory outflows exceed income (then lines is empty). */
    public record AllocationView(LocalDate cycleStart, long surplusPaise, long shortfallPaise,
            long allocatedPaise, String prepayShare, List<Line> lines) {
    }

    private final PlanCycleService cycles;
    private final IncomeService income;
    private final LoanService loans;
    private final RecurringService recurring;
    private final GoalService goals;
    private final EmergencyFundService emergencyFund;
    private final AllocationRepository repo;
    private final ConsentService consents;
    private final RuleConfig config;
    private final ObjectMapper mapper;

    public AllocationService(PlanCycleService cycles, IncomeService income, LoanService loans,
            RecurringService recurring, GoalService goals, EmergencyFundService emergencyFund,
            AllocationRepository repo, ConsentService consents, RuleConfig config, ObjectMapper mapper) {
        this.cycles = cycles;
        this.income = income;
        this.loans = loans;
        this.recurring = recurring;
        this.goals = goals;
        this.emergencyFund = emergencyFund;
        this.repo = repo;
        this.consents = consents;
        this.config = config;
        this.mapper = mapper;
    }

    /** prepayShare is a decimal fraction 0..1 of the step-7 remainder aimed at prepayment; null = default 30%. */
    public AllocationView run(UUID userId, String prepayShare) {
        BigDecimal share = parseShare(prepayShare);
        consents.require(userId);
        LocalDate cycleStart = cycles.current(userId).cycleStart();
        var ef = emergencyFund.size(userId, null);
        List<Money> mandatory = new ArrayList<>();
        mandatory.add(Money.ofPaise(loans.guard(userId).totalEmiPaise()));
        recurring.list(userId).forEach(t -> mandatory
                .add(Money.ofPaise(t.currentInstalmentPaise() == null ? 0L : t.currentInstalmentPaise())));
        WaterfallAllocator.Result r;
        try {
            r = WaterfallAllocator.allocate(new WaterfallAllocator.Input(
                    Money.ofPaise(income.cycleTotal(userId).totalPaise()), mandatory,
                    Money.ofPaise(ef.monthlyOutflowPaise()), Money.ofPaise(ef.currentPaise()), ef.targetMonths(),
                    loans.debtTargets(userId), goals.goalTargets(userId), share), config);
        } catch (IllegalArgumentException | ArithmeticException | com.finpath.goal.InvalidGoalException
                | com.finpath.loan.InvalidLoanException e) {
            throw new InvalidAllocationException();
        }
        List<Line> lines = r.lines().stream()
                .map(l -> new Line(l.step().name(), l.ref(), l.amount().paise())).toList();
        try {
            if (!repo.save(userId, cycleStart, r.surplus().paise(), mapper.writeValueAsString(lines))) {
                throw new AllocationNotFoundException();
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("allocations not serialisable");
        }
        return new AllocationView(cycleStart, r.surplus().paise(), r.shortfall().paise(), r.total().paise(),
                share.toPlainString(), lines);
    }

    /** The stored result for the open cycle; 404 until a run has happened. */
    public AllocationView current(UUID userId) {
        Stored s = repo.open(userId).orElseThrow(AllocationNotFoundException::new);
        if (s.surplusPaise() == null) {
            throw new AllocationNotFoundException();
        }
        try {
            List<Line> lines = mapper.readValue(s.allocationsJson(), new TypeReference<List<Line>>() { });
            long allocated = BudgetRules.sum(lines.stream().map(l -> Money.ofPaise(l.amountPaise())).toList()).paise();
            long shortfall = Math.max(0L, -s.surplusPaise());
            return new AllocationView(s.cycleStart(), s.surplusPaise(), shortfall, allocated, null, lines);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored allocations are not in the documented format");
        }
    }

    private static BigDecimal parseShare(String raw) {
        if (raw == null) {
            return RuleConfig.DEFAULT_PREPAY_SHARE;
        }
        try {
            BigDecimal v = new BigDecimal(raw);
            if (v.signum() < 0 || v.compareTo(BigDecimal.ONE) > 0) {
                throw new InvalidAllocationException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new InvalidAllocationException();
        }
    }
}
