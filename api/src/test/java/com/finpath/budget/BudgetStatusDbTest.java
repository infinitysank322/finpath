package com.finpath.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finpath.budget.BudgetService.BudgetInput;
import com.finpath.budget.BudgetService.Line;
import com.finpath.budget.BudgetService.StatusView;
import com.finpath.dpdp.ConsentRequiredException;
import com.finpath.rules.BudgetRules;
import com.finpath.rules.BudgetRules.Status;
import com.finpath.rules.Money;
import com.finpath.rules.RuleConfig;
import com.finpath.rules.TransactionHash.Direction;
import com.finpath.rules.golden.GoldenFixture;
import com.finpath.rules.golden.GoldenKeys;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T40 acceptance: status lines equal BudgetRules.status over the same fixture values, under RLS (skipped without a DB). */
class BudgetStatusDbTest {

    private static final long BUDGET = GoldenFixture.shared().paise(GoldenKeys.BUDGET_CATEGORY);
    private static final long SPENT_WARN = GoldenFixture.shared().paise(GoldenKeys.BUDGET_SPENT_WARN);
    private static final LocalDate CYCLE = LocalDate.parse("2026-10-01");

    private BudgetDbFixture f;
    private UUID a;
    private UUID b;

    @BeforeEach
    void setUp() throws Exception {
        f = new BudgetDbFixture();
        a = f.newUser(true);
        b = f.newUser(true);
    }

    @AfterEach
    void clear() {
        BudgetDbFixture.clearPrincipal();
    }

    @Test
    void lineEqualsTheRulesStatusAndIgnoresInRowsAndRowsOutsideTheCycle() throws Exception {
        f.spend(a, Direction.OUT, "DINING", SPENT_WARN, BudgetDbFixture.IN_CYCLE);
        f.spend(a, Direction.IN, "REFUND", SPENT_WARN, BudgetDbFixture.IN_CYCLE);
        f.spend(a, Direction.OUT, "DINING", SPENT_WARN, BudgetDbFixture.BEFORE_CYCLE);
        f.spend(a, Direction.OUT, "DINING", SPENT_WARN, BudgetDbFixture.AFTER_CYCLE);
        f.spend(a, Direction.OUT, "FUEL", SPENT_WARN, BudgetDbFixture.IN_CYCLE);
        BudgetDbFixture.as(a);
        f.service.put(a, "dining", null, new BudgetInput(BUDGET));

        Status expected = BudgetRules.status(Money.ofPaise(BUDGET), Money.ofPaise(SPENT_WARN),
                RuleConfig.defaults());
        StatusView view = f.service.status(a, null);

        assertThat(view.cycleStart()).isEqualTo(CYCLE);
        assertThat(view.cycleEndExclusive()).isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(view.lines()).hasSize(1);
        Line l = view.lines().get(0);
        assertThat(l.categoryCode()).isEqualTo("DINING");
        assertThat(l.budgetPaise()).isEqualTo(BUDGET);
        assertThat(l.spentPaise()).isEqualTo(SPENT_WARN);
        assertThat(l.remainingPaise()).isEqualTo(expected.remaining().paise());
        assertThat(l.usedFraction()).isEqualTo(expected.usedFraction().toPlainString());
        assertThat(l.level()).isEqualTo(expected.level());
        assertThat(l.lastAlertLevel()).isEqualTo(BudgetRules.Level.OK);   // GET never fires an alert

        f.mvc.perform(get("/budgets/status").param("cycleStart", "2026-10-15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleStart").value("2026-10-01"))
                .andExpect(jsonPath("$.lines[0].spentPaise").value(SPENT_WARN))
                .andExpect(jsonPath("$.lines[0].remainingPaise").value(expected.remaining().paise()))
                .andExpect(jsonPath("$.lines[0].level").value(expected.level().name()))
                .andExpect(jsonPath("$.lines[0].lastAlertLevel").value("OK"));
    }

    @Test
    void aBudgetWithNoSpendHasZeroSpentAndFullRemaining() {
        BudgetDbFixture.as(a);
        Line l = f.service.put(a, "TRANSPORT", null, new BudgetInput(BUDGET));
        assertThat(l.spentPaise()).isZero();
        assertThat(l.remainingPaise()).isEqualTo(BUDGET);
        assertThat(l.level()).isEqualTo(BudgetRules.Level.OK);
    }

    @Test
    void putTwiceKeepsOneLineAndDeleteRemovesIt() throws Exception {
        BudgetDbFixture.as(a);
        f.service.put(a, "DINING", null, new BudgetInput(BUDGET));
        f.service.put(a, "DINING", null, new BudgetInput(BUDGET + 100));
        assertThat(f.service.status(a, null).lines()).hasSize(1);
        assertThat(f.service.status(a, null).lines().get(0).budgetPaise()).isEqualTo(BUDGET + 100);
        f.mvc.perform(delete("/budgets/DINING")).andExpect(status().isNoContent());
        assertThat(f.service.status(a, null).lines()).isEmpty();
        f.mvc.perform(delete("/budgets/DINING")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void userBSeesNothingOfAAndCannotDeleteAsLine() throws Exception {
        BudgetDbFixture.as(a);
        f.service.put(a, "DINING", null, new BudgetInput(BUDGET));
        f.spend(a, Direction.OUT, "DINING", SPENT_WARN, BudgetDbFixture.IN_CYCLE);
        BudgetDbFixture.as(b);
        assertThat(f.service.status(b, null).lines()).isEmpty();
        assertThatThrownBy(() -> f.service.delete(b, "DINING", null)).isInstanceOf(BudgetNotFoundException.class);
        BudgetDbFixture.as(a);
        assertThat(f.service.status(a, null).lines()).hasSize(1);
    }

    @Test
    void invalidInputIsRejectedAndWritesNeedConsent() throws Exception {
        BudgetDbFixture.as(a);
        assertThatThrownBy(() -> f.service.put(a, "NOPE", null, new BudgetInput(BUDGET)))
                .isInstanceOf(InvalidBudgetException.class);
        assertThatThrownBy(() -> f.service.put(a, "INCOME", null, new BudgetInput(BUDGET)))
                .isInstanceOf(InvalidBudgetException.class);
        assertThatThrownBy(() -> f.service.put(a, "DINING", null, new BudgetInput(-1L)))
                .isInstanceOf(InvalidBudgetException.class);
        assertThatThrownBy(() -> f.service.put(a, "DINING", null, new BudgetInput(null)))
                .isInstanceOf(InvalidBudgetException.class);
        UUID c = f.newUser(false);
        BudgetDbFixture.as(c);
        assertThatThrownBy(() -> f.service.put(c, "DINING", null, new BudgetInput(BUDGET)))
                .isInstanceOf(ConsentRequiredException.class);
        assertThatThrownBy(() -> f.service.delete(c, "DINING", null)).isInstanceOf(ConsentRequiredException.class);
        assertThatThrownBy(() -> f.alerts.evaluate(c, null)).isInstanceOf(ConsentRequiredException.class);
    }
}
