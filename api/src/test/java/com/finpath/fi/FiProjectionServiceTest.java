package com.finpath.fi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpath.fi.FiPlannerService.FiPlanView;
import com.finpath.fi.FiProjectionService.Projection;
import com.finpath.holding.HoldingService;
import com.finpath.holding.HoldingService.AggregateView;
import com.finpath.holding.HoldingService.ClassTotalView;
import com.finpath.income.IncomeService.CycleItem;
import com.finpath.income.IncomeService.CycleTotalView;
import com.finpath.rules.IncomeCalculator.Type;
import com.finpath.rules.RuleConfig;
import com.finpath.rules.golden.GoldenFixture;
import com.finpath.rules.golden.GoldenKeys;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T74: the dashboard FI projection uses stored inputs only and equals the stateless planner on the same inputs. */
class FiProjectionServiceTest {

    private static final GoldenFixture FX = GoldenFixture.shared();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 22);
    private static final long OUTFLOW = FX.paise(GoldenKeys.FI_TODAY_EXPENSE);
    private static final long RENTAL = FX.paise(GoldenKeys.INCOME_RENTAL);
    private static final long SALARY = FX.paise(GoldenKeys.INCOME_SALARY);
    private static final long OPENING = FX.paise(GoldenKeys.EMERGENCY_CURRENT);

    private final UUID user = UUID.randomUUID();
    private final FiPlannerService planner = new FiPlannerService(RuleConfig.defaults());
    private HoldingService holdings;
    private FiProjectionService service;

    @BeforeEach
    void setUp() {
        holdings = mock(HoldingService.class);
        service = new FiProjectionService(planner, holdings, JSON);
        holdingsTotal(OPENING);
    }

    private void holdingsTotal(long value) {
        when(holdings.aggregate(user)).thenReturn(new AggregateView(List.of(),
                new ClassTotalView("total", value, value, 0L, 1L)));
    }

    private static CycleTotalView income() {
        return new CycleTotalView(AS_OF, AS_OF.plusMonths(1), SALARY + RENTAL, List.of(
                new CycleItem(UUID.randomUUID(), Type.SALARY, SALARY),
                new CycleItem(UUID.randomUUID(), Type.RENTAL, RENTAL)));
    }

    private static com.fasterxml.jackson.databind.JsonNode alloc(long habit, long longTerm, long other) throws Exception {
        return JSON.readTree("[{\"step\":\"HABIT_SIP\",\"ref\":null,\"amountPaise\":" + habit
                + "},{\"step\":\"LONG_TERM_SIP\",\"ref\":null,\"amountPaise\":" + longTerm
                + "},{\"step\":\"SAFE_GOALS\",\"ref\":\"g\",\"amountPaise\":" + other + "}]");
    }

    @Test
    void equalsThePlannerOnTheSameStoredInputs() throws Exception {
        long habit = 100_000L;
        long longTerm = 250_000L;
        Projection p = service.projection(user, OUTFLOW, income(), alloc(habit, longTerm, 999_999L), AS_OF);
        FiPlanView direct = planner.plan(OUTFLOW, RENTAL, OPENING, habit + longTerm, null, 1, AS_OF);
        assertThat(p.setUp()).isTrue();
        assertThat(p.plan().bands()).isEqualTo(direct.bands());
        assertThat(p.plan().assumptions()).isEqualTo(direct.assumptions());
        assertThat(p.plan().disclaimer()).isNotBlank();
        assertThat(p.plan().bands()).hasSize(3);
    }

    @Test
    void notSetUpWithoutHoldingsAndWithoutSip() throws Exception {
        holdingsTotal(0L);
        assertThat(service.projection(user, OUTFLOW, income(), JSON.readTree("[]"), AS_OF).setUp()).isFalse();
    }

    @Test
    void setUpFromSipAloneOrHoldingsAlone() throws Exception {
        holdingsTotal(0L);
        assertThat(service.projection(user, OUTFLOW, income(), alloc(1L, 0L, 0L), AS_OF).setUp()).isTrue();
        holdingsTotal(OPENING);
        assertThat(service.projection(user, OUTFLOW, income(), JSON.readTree("[]"), AS_OF).setUp()).isTrue();
    }

    @Test
    void notSetUpWithoutAnyOutflow() throws Exception {
        assertThat(service.projection(user, 0L, income(), alloc(1L, 1L, 0L), AS_OF).setUp()).isFalse();
    }

    @Test
    void nullAllocationsAreEmpty() {
        assertThat(service.projection(user, OUTFLOW, income(), null, AS_OF).setUp()).isTrue();
    }
}
