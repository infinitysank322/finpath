package com.finpath.budget;

import static org.assertj.core.api.Assertions.assertThat;

import com.finpath.budget.BudgetAlertService.Event;
import com.finpath.budget.BudgetService.BudgetInput;
import com.finpath.rules.BudgetRules.Level;
import com.finpath.rules.TransactionHash.Direction;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Threshold events fire once each, against the scratch database under RLS (skipped without one). */
class BudgetAlertDbTest {

    /** Round fake figures: 79.9% = 799_000, 80% = 800_000, 100% = 1_000_000 of this budget. */
    private static final long BUDGET = 1_000_000;
    private static final LocalDate DAY = BudgetDbFixture.IN_CYCLE;
    private static final LocalDate CYCLE = LocalDate.parse("2026-10-01");

    private BudgetDbFixture f;
    private UUID a;

    @BeforeEach
    void setUp() throws Exception {
        f = new BudgetDbFixture();
        a = f.newUser(true);
        BudgetDbFixture.as(a);
        f.service.put(a, "DINING", null, new BudgetInput(BUDGET));
    }

    @AfterEach
    void clear() {
        BudgetDbFixture.clearPrincipal();
    }

    private java.util.List<Event> evaluate() {
        BudgetDbFixture.as(a);
        return f.alerts.evaluate(a, null);
    }

    @Test
    void warnFiresOnceThenBreachFiresOnce() {
        f.spend(a, Direction.OUT, "DINING", 799_000, DAY);
        assertThat(evaluate()).isEmpty();                                       // 79.9%
        f.spend(a, Direction.OUT, "DINING", 1_000, DAY);                        // 80.0%
        assertThat(evaluate()).containsExactly(new Event("DINING", Level.WARN));
        assertThat(evaluate()).isEmpty();                                       // repeat
        f.spend(a, Direction.OUT, "DINING", 200_000, DAY);                      // 100%
        assertThat(evaluate()).containsExactly(new Event("DINING", Level.BREACH));
        assertThat(evaluate()).isEmpty();
        f.spend(a, Direction.OUT, "DINING", 50_000, DAY);                       // more overspend
        assertThat(evaluate()).isEmpty();
    }

    @Test
    void jumpingStraightToTheLimitFiresBreachOnlyAndNeverWarn() {
        f.spend(a, Direction.OUT, "DINING", 1_000_000, DAY);
        assertThat(evaluate()).containsExactly(new Event("DINING", Level.BREACH));
        assertThat(evaluate()).isEmpty();
    }

    @Test
    void changingTheBudgetResetsTheLevelSoItCanAlertAgain() {
        f.spend(a, Direction.OUT, "DINING", 1_000_000, DAY);
        assertThat(evaluate()).hasSize(1);
        BudgetDbFixture.as(a);
        f.service.put(a, "DINING", null, new BudgetInput(2_000_000L));           // now 50%
        assertThat(f.service.status(a, null).lines().get(0).lastAlertLevel()).isEqualTo(Level.OK);
        assertThat(evaluate()).isEmpty();
        f.spend(a, Direction.OUT, "DINING", 600_000, DAY);                      // 80% of the new limit
        assertThat(evaluate()).containsExactly(new Event("DINING", Level.WARN));
    }

    @Test
    void savingTheSameAmountKeepsTheFiredLevel() {
        f.spend(a, Direction.OUT, "DINING", 800_000, DAY);
        assertThat(evaluate()).hasSize(1);
        BudgetDbFixture.as(a);
        f.service.put(a, "DINING", null, new BudgetInput(BUDGET));
        assertThat(evaluate()).isEmpty();
    }

    @Test
    void levelDoesNotLowerWhenSpendFalls() {
        f.spend(a, Direction.OUT, "DINING", 800_000, DAY);
        assertThat(evaluate()).hasSize(1);
        BudgetDbFixture.as(a);
        assertThat(f.service.status(a, null).lines().get(0).lastAlertLevel()).isEqualTo(Level.WARN);
    }

    @Test
    void onlyOneOfTwoCompareAndSetCallsWins() {
        BudgetDbFixture.as(a);
        UUID id = f.repository.listForCycle(a, CYCLE).get(0).id();
        assertThat(f.repository.advanceLevel(a, id, Level.OK, Level.WARN)).isTrue();
        assertThat(f.repository.advanceLevel(a, id, Level.OK, Level.WARN)).isFalse();
    }

    @Test
    void spendOutsideTheCycleOrInOtherCategoriesDoesNotAlert() {
        f.spend(a, Direction.OUT, "DINING", 1_000_000, BudgetDbFixture.BEFORE_CYCLE);
        f.spend(a, Direction.OUT, "FUEL", 1_000_000, DAY);
        f.spend(a, Direction.IN, "REFUND", 1_000_000, DAY);
        assertThat(evaluate()).isEmpty();
    }

    @Test
    void anotherUsersEvaluateNeverFiresOrChangesAsBudget() throws Exception {
        f.spend(a, Direction.OUT, "DINING", 800_000, DAY);
        UUID b = f.newUser(true);
        BudgetDbFixture.as(b);
        assertThat(f.alerts.evaluate(b, null)).isEmpty();
        assertThat(evaluate()).hasSize(1);
    }
}
