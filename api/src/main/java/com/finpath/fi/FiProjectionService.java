package com.finpath.fi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpath.fi.FiPlannerService.FiPlanView;
import com.finpath.holding.HoldingService;
import com.finpath.income.IncomeService.CycleTotalView;
import com.finpath.rebalance.AllocationsJson;
import com.finpath.rules.BudgetRules;
import com.finpath.rules.IncomeCalculator;
import com.finpath.rules.Money;
import com.finpath.rules.WaterfallAllocator;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * FR-21 projection from stored data only (nothing typed): expense from the emergency-fund outflow, passive from
 * the cycle's rental income, opening from holdings, SIP from the allocation's SIP lines, step-up 0. The figures
 * come from {@link FiPlannerService}; this class gathers inputs and sums them with {@link BudgetRules#sum}.
 * fiYears / fiDate do not depend on the horizon, so the minimum valid one is used and corpus fields are ignored.
 */
@Service
public class FiProjectionService {

    static final int MIN_HORIZON_YEARS = 1;

    /** setUp is false when there is nothing to project from (no outflow, or no holdings and no SIP). */
    public record Projection(boolean setUp, FiPlanView plan) {
    }

    private final FiPlannerService planner;
    private final HoldingService holdings;
    private final ObjectMapper mapper;

    public FiProjectionService(FiPlannerService planner, HoldingService holdings, ObjectMapper mapper) {
        this.planner = planner;
        this.holdings = holdings;
        this.mapper = mapper;
    }

    public Projection projection(UUID userId, long monthlyOutflowPaise, CycleTotalView income,
            JsonNode allocations, LocalDate asOf) {
        Money passive = BudgetRules.sum(income.items().stream()
                .filter(i -> i.type() == IncomeCalculator.Type.RENTAL).map(i -> Money.ofPaise(i.amountPaise()))
                .toList());
        long opening = holdings.aggregate(userId).total().currentValuePaise();
        List<WaterfallAllocator.Line> lines = allocations == null || allocations.isNull() ? List.of()
                : AllocationsJson.parse(mapper, allocations.toString());
        Money sip = BudgetRules.sum(lines.stream()
                .filter(l -> l.step() == WaterfallAllocator.Step.HABIT_SIP
                        || l.step() == WaterfallAllocator.Step.LONG_TERM_SIP)
                .map(WaterfallAllocator.Line::amount).toList());
        FiPlanView plan = planner.plan(monthlyOutflowPaise, passive.paise(), opening, sip.paise(), null,
                MIN_HORIZON_YEARS, asOf);
        boolean setUp = monthlyOutflowPaise > 0 && (opening > 0 || sip.paise() > 0);
        return new Projection(setUp, plan);
    }
}
