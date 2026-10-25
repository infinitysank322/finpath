package com.finpath.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finpath.budget.BudgetRepository.Row;
import com.finpath.dpdp.ConsentRequiredException;
import com.finpath.dpdp.ConsentService;
import com.finpath.expense.TransactionRepository;
import com.finpath.expense.TransactionRepository.Transaction;
import com.finpath.rules.BudgetRules.Level;
import com.finpath.rules.CycleCalendar;
import com.finpath.rules.RuleConfig;
import com.finpath.rules.TransactionHash.Direction;
import com.finpath.rules.golden.GoldenFixture;
import com.finpath.rules.golden.GoldenKeys;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T74: BudgetService.cycleSummary rolls the cycle up through the rules; figures come from the fixture. */
class BudgetCycleSummaryTest {

    private static final GoldenFixture FX = GoldenFixture.shared();
    private static final long BUDGET = FX.paise(GoldenKeys.BUDGET_CATEGORY);
    private static final long SPENT = FX.paise(GoldenKeys.BUDGET_SPENT);
    private static final long BREACH = FX.paise(GoldenKeys.BUDGET_SPENT_BREACH);
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 22);
    private static final CycleCalendar.Cycle CYCLE = CycleCalendar.cycleFor(AS_OF, 1);

    private final UUID user = UUID.randomUUID();
    private BudgetRepository repo;
    private TransactionRepository txns;
    private ConsentService consents;
    private BudgetService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        repo = mock(BudgetRepository.class);
        txns = mock(TransactionRepository.class);
        consents = mock(ConsentService.class);
        when(repo.inTransaction(any())).thenAnswer(i -> ((Supplier<Object>) i.getArgument(0)).get());
        when(repo.cycleOf(user, AS_OF)).thenReturn(CYCLE);
        service = new BudgetService(repo, txns, consents, RuleConfig.defaults());
    }

    private static Row line(String code, long budget) {
        return new Row(UUID.randomUUID(), code, budget, Level.OK);
    }

    private static Transaction out(String code, long amount) {
        return new Transaction(UUID.randomUUID(), AS_OF, amount, Direction.OUT, code, "UPI", null, "manual", null,
                "h" + amount + code, null);
    }

    @Test
    void emptyWhenNoBudgetLines() {
        when(repo.listForCycle(user, CYCLE.start())).thenReturn(List.of());
        assertThat(service.cycleSummary(user, AS_OF)).isEmpty();
    }

    @Test
    void totalsIncludeUnbudgetedSpendAndSafeToSpendIsFlooredPerDayLeft() {
        when(repo.listForCycle(user, CYCLE.start())).thenReturn(List.of(line("FOOD", BUDGET), line("FUEL", BUDGET)));
        when(txns.listBetween(user, CYCLE.start(), CYCLE.endExclusive()))
                .thenReturn(List.of(out("FOOD", SPENT), out("TRAVEL", SPENT)));
        var s = service.cycleSummary(user, AS_OF).orElseThrow();
        long budget = 2 * BUDGET;
        long spent = 2 * SPENT;
        assertThat(s.budgetPaise()).isEqualTo(budget);
        assertThat(s.spentPaise()).as("unbudgeted TRAVEL counts, as in the rebalance preview").isEqualTo(spent);
        assertThat(s.remainingPaise()).isEqualTo(budget - spent);
        assertThat(s.level()).isEqualTo(Level.OK);
        assertThat(s.safeToSpendPerDayPaise())
                .isEqualTo(Math.floorDiv(budget - spent, CycleCalendar.daysLeft(CYCLE, AS_OF)));
        assertThat(s.usedFraction()).isNotNull();
    }

    @Test
    void overBudgetGivesZeroSafeToSpendAndNegativeRemaining() {
        when(repo.listForCycle(user, CYCLE.start())).thenReturn(List.of(line("FOOD", BUDGET)));
        when(txns.listBetween(user, CYCLE.start(), CYCLE.endExclusive())).thenReturn(List.of(out("FOOD", BREACH)));
        var s = service.cycleSummary(user, AS_OF).orElseThrow();
        assertThat(s.safeToSpendPerDayPaise()).isZero();
        assertThat(s.remainingPaise()).isEqualTo(BUDGET - BREACH).isNegative();
        assertThat(s.level()).isEqualTo(Level.BREACH);
    }

    @Test
    void zeroBudgetHasNoUsedFraction() {
        when(repo.listForCycle(user, CYCLE.start())).thenReturn(List.of(line("FOOD", 0L)));
        when(txns.listBetween(user, CYCLE.start(), CYCLE.endExclusive())).thenReturn(List.of());
        var s = service.cycleSummary(user, AS_OF).orElseThrow();
        assertThat(s.usedFraction()).isNull();
        assertThat(s.safeToSpendPerDayPaise()).isZero();
    }

    @Test
    void requiresConsentBeforeReading() {
        doThrow(new ConsentRequiredException()).when(consents).require(user);
        assertThatThrownBy(() -> service.cycleSummary(user, AS_OF)).isInstanceOf(ConsentRequiredException.class);
        verify(repo, never()).listForCycle(any(), any());
    }

    @Test
    void otherUsersRowsAreNeverRequested() {
        when(repo.listForCycle(user, CYCLE.start())).thenReturn(List.of());
        service.cycleSummary(user, AS_OF);
        verify(repo).listForCycle(user, CYCLE.start());
    }
}
