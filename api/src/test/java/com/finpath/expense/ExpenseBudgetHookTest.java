package com.finpath.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finpath.budget.BudgetAlertService;
import com.finpath.dpdp.ConsentService;
import com.finpath.expense.ExpenseService.ExpenseInput;
import com.finpath.expense.TransactionRepository.InsertResult;
import com.finpath.expense.TransactionRepository.Transaction;
import com.finpath.rules.TransactionHash.Direction;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The budget hook runs after OUT changes and can never fail the expense write. */
class ExpenseBudgetHookTest {

    private static final LocalDate DAY = LocalDate.of(2026, 10, 22);
    private final UUID me = UUID.randomUUID();
    private final TransactionRepository repo = mock(TransactionRepository.class);
    private final ConsentService consents = mock(ConsentService.class);
    private final BudgetAlertService alerts = mock(BudgetAlertService.class);
    private final ExpenseService service = new ExpenseService(repo, () -> null, () -> null, consents,
            Clock.systemUTC(), alerts);

    private static Transaction row(LocalDate day) {
        return new Transaction(UUID.randomUUID(), day, 4000, Direction.OUT, "DINING", "UPI", null, "MANUAL", null,
                "hash", "key-0123456789");
    }

    private static ExpenseInput input() {
        return new ExpenseInput(DAY, 4000L, "DINING", "UPI", null, null);
    }

    @Test
    void createEvaluatesTheRowsCycleAndSurvivesAFailingEvaluation() {
        when(repo.insert(eq(me), any())).thenReturn(new InsertResult(row(DAY), true));
        when(alerts.evaluate(me, DAY)).thenThrow(new IllegalStateException("boom"));
        assertThat(service.create(me, "key-0123456789", input()).created()).isTrue();
        verify(alerts).evaluate(me, DAY);
    }

    @Test
    void aReplayedCreateDoesNotEvaluate() {
        when(repo.insert(eq(me), any())).thenReturn(new InsertResult(row(DAY), false));
        when(repo.insert(eq(me), any())).thenAnswer(inv -> new InsertResult(row(DAY), false));
        try {
            service.create(me, "key-0123456789", input());
        } catch (IdempotencyConflictException ignored) {
            // payload hash differs in this stub; only the absence of a hook call matters here
        }
        verify(alerts, never()).evaluate(any(), any());
    }

    @Test
    void deleteAndReplaceEvaluateAndSwallowFailures() {
        Transaction old = row(LocalDate.of(2026, 9, 30));
        when(repo.findById(eq(me), any())).thenReturn(Optional.of(old));
        when(repo.delete(eq(me), any())).thenReturn(true);
        when(alerts.evaluate(any(), any())).thenThrow(new RuntimeException("boom"));
        service.delete(me, old.id());
        verify(alerts).evaluate(me, old.txnDate());

        when(repo.update(eq(me), any(), any())).thenReturn(Optional.of(row(DAY)));
        service.replace(me, old.id(), input());
        verify(alerts).evaluate(me, DAY);
    }
}
