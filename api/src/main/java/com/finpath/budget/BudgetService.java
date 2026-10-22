package com.finpath.budget;

import com.finpath.budget.BudgetRepository.Row;
import com.finpath.dpdp.ConsentService;
import com.finpath.expense.TransactionRepository;
import com.finpath.rules.BudgetRules;
import com.finpath.rules.BudgetRules.Level;
import com.finpath.rules.BudgetRules.Status;
import com.finpath.rules.CycleCalendar;
import com.finpath.rules.Money;
import com.finpath.rules.RuleConfig;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Per-category cycle budgets and their status. Every figure in a status line comes from
 * {@link BudgetRules#status} or the stored row; this class does no money arithmetic. Nothing here
 * logs amounts. GET status is read-only and never touches last_alert_level.
 */
@Service
public class BudgetService {

    static final ZoneId CYCLE_ZONE = ZoneId.of("Asia/Kolkata");
    /** Categories that exist for direction IN only (V6 seed): they can carry no spend limit. */
    private static final Set<String> IN_ONLY = Set.of("INCOME", "REFUND", "OTHER_IN");

    /** Write payload; the owner is never part of it. */
    public record BudgetInput(Long budgetPaise) {
    }

    public record Line(String categoryCode, long budgetPaise, long spentPaise, long remainingPaise,
            String usedFraction, Level level, Level lastAlertLevel) {
    }

    public record StatusView(LocalDate cycleStart, LocalDate cycleEndExclusive, List<Line> lines) {
    }

    private final BudgetRepository budgets;
    private final TransactionRepository transactions;
    private final ConsentService consents;
    private final RuleConfig config;
    private final Clock clock;

    @Autowired
    public BudgetService(BudgetRepository budgets, TransactionRepository transactions, ConsentService consents,
            RuleConfig config) {
        this(budgets, transactions, consents, config, Clock.system(CYCLE_ZONE));
    }

    BudgetService(BudgetRepository budgets, TransactionRepository transactions, ConsentService consents,
            RuleConfig config, Clock clock) {
        this.budgets = budgets;
        this.transactions = transactions;
        this.consents = consents;
        this.config = config;
        this.clock = clock;
    }

    /** Upserts the line and returns it with its current status. */
    public Line put(UUID userId, String categoryCode, LocalDate cycleStart, BudgetInput in) {
        String code = category(categoryCode);
        if (in == null || in.budgetPaise() == null || in.budgetPaise() < 0) {
            throw new InvalidBudgetException();
        }
        consents.require(userId);
        return budgets.inTransaction(() -> {
            CycleCalendar.Cycle cycle = budgets.cycleOf(userId, day(cycleStart));
            budgets.upsert(userId, cycle.start(), code, in.budgetPaise()).orElseThrow(InvalidBudgetException::new);
            return lines(userId, cycle).stream().filter(l -> l.categoryCode().equals(code)).findFirst()
                    .orElseThrow(BudgetNotFoundException::new);
        });
    }

    public void delete(UUID userId, String categoryCode, LocalDate cycleStart) {
        String code = category(categoryCode);
        consents.require(userId);
        budgets.inTransaction(() -> {
            CycleCalendar.Cycle cycle = budgets.cycleOf(userId, day(cycleStart));
            if (!budgets.delete(userId, cycle.start(), code)) {
                throw new BudgetNotFoundException();
            }
            return null;
        });
    }

    public StatusView status(UUID userId, LocalDate cycleStart) {
        return budgets.inTransaction(() -> {
            CycleCalendar.Cycle cycle = budgets.cycleOf(userId, day(cycleStart));
            return new StatusView(cycle.start(), cycle.endExclusive(), lines(userId, cycle));
        });
    }

    private List<Line> lines(UUID userId, CycleCalendar.Cycle cycle) {
        List<Row> rows = budgets.listForCycle(userId, cycle.start());
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<String, Money> spent = BudgetSpend.byCategory(transactions, userId, cycle);
        return rows.stream().map(r -> {
            Status s = BudgetRules.status(Money.ofPaise(r.budgetPaise()),
                    spent.getOrDefault(r.categoryCode(), Money.ZERO), config);
            return new Line(r.categoryCode(), s.budget().paise(), s.spent().paise(), s.remaining().paise(),
                    s.usedFraction() == null ? null : s.usedFraction().toPlainString(), s.level(),
                    r.lastAlertLevel());
        }).toList();
    }

    private LocalDate day(LocalDate requested) {
        return requested == null ? LocalDate.now(clock) : requested;
    }

    private static String category(String raw) {
        String code = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty() || IN_ONLY.contains(code)) {
            throw new InvalidBudgetException();
        }
        return code;
    }
}
