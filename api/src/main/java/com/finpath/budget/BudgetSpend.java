package com.finpath.budget;

import com.finpath.expense.TransactionRepository;
import com.finpath.rules.BudgetRules;
import com.finpath.rules.BudgetRules.Spend;
import com.finpath.rules.CycleCalendar;
import com.finpath.rules.Money;
import java.util.Map;
import java.util.UUID;

/** Loads a cycle's transactions and hands them to the rules; the summing is BudgetRules', not ours. */
final class BudgetSpend {

    private BudgetSpend() {
    }

    static Map<String, Money> byCategory(TransactionRepository transactions, UUID userId,
            CycleCalendar.Cycle cycle) {
        return BudgetRules.spentByCategory(
                transactions.listBetween(userId, cycle.start(), cycle.endExclusive()).stream()
                        .map(t -> new Spend(t.categoryCode(), t.direction(), t.amountPaise())).toList());
    }
}
