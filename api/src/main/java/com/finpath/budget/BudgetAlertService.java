package com.finpath.budget;

import com.finpath.budget.BudgetRepository.Row;
import com.finpath.dpdp.ConsentService;
import com.finpath.expense.TransactionRepository;
import com.finpath.rules.BudgetRules;
import com.finpath.rules.BudgetRules.Level;
import com.finpath.rules.CycleCalendar;
import com.finpath.rules.Money;
import com.finpath.rules.RuleConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Fires each threshold event once. The level to announce comes from {@link BudgetRules}; the event
 * is emitted only if the compare-and-set on last_alert_level changed a row, so two concurrent
 * callers cannot both announce. Level never lowers when spend falls. Nothing here logs amounts.
 */
@Service
public class BudgetAlertService {

    public record Event(String categoryCode, Level level) {
    }

    private final BudgetRepository budgets;
    private final TransactionRepository transactions;
    private final ConsentService consents;
    private final RuleConfig config;
    private final Clock clock;

    @Autowired
    public BudgetAlertService(BudgetRepository budgets, TransactionRepository transactions,
            ConsentService consents, RuleConfig config) {
        this(budgets, transactions, consents, config, Clock.system(BudgetService.CYCLE_ZONE));
    }

    BudgetAlertService(BudgetRepository budgets, TransactionRepository transactions, ConsentService consents,
            RuleConfig config, Clock clock) {
        this.budgets = budgets;
        this.transactions = transactions;
        this.consents = consents;
        this.config = config;
        this.clock = clock;
    }

    /** Evaluates the cycle containing {@code anyDate} (null = today) and returns the events fired by this call. */
    public List<Event> evaluate(UUID userId, LocalDate anyDate) {
        consents.require(userId);
        return budgets.inTransaction(() -> {
            CycleCalendar.Cycle cycle = budgets.cycleOf(userId, anyDate == null ? LocalDate.now(clock) : anyDate);
            List<Row> rows = budgets.listForCycle(userId, cycle.start());
            if (rows.isEmpty()) {
                return List.<Event>of();
            }
            Map<String, Money> spent = BudgetSpend.byCategory(transactions, userId, cycle);
            List<Event> events = new ArrayList<>();
            for (Row r : rows) {
                Level current = BudgetRules.levelFor(Money.ofPaise(r.budgetPaise()),
                        spent.getOrDefault(r.categoryCode(), Money.ZERO), config);
                Optional<Level> alert = BudgetRules.newAlert(r.lastAlertLevel(), current);
                if (alert.isPresent() && budgets.advanceLevel(userId, r.id(), r.lastAlertLevel(), alert.get())) {
                    events.add(new Event(r.categoryCode(), alert.get()));
                }
            }
            return List.copyOf(events);
        });
    }
}
