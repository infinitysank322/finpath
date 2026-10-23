package com.finpath.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpath.allocation.AllocationService.AllocationView;
import com.finpath.dpdp.ConsentService;
import com.finpath.goal.EmergencyFundService;
import com.finpath.goal.EmergencyFundService.EmergencyFundView;
import com.finpath.goal.GoalService;
import com.finpath.income.IncomeService;
import com.finpath.income.IncomeService.CycleTotalView;
import com.finpath.loan.LoanService;
import com.finpath.loan.LoanService.GuardView;
import com.finpath.plan.PlanCycleService;
import com.finpath.plan.PlanCycleService.PlanCycleView;
import com.finpath.recurring.RecurringService;
import com.finpath.recurring.RecurringService.TemplateView;
import com.finpath.rules.Money;
import com.finpath.rules.RuleConfig;
import com.finpath.rules.WaterfallAllocator;
import com.finpath.rules.golden.GoldenAssert;
import com.finpath.rules.golden.GoldenFixture;
import com.finpath.rules.golden.GoldenKeys;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** T64: the run hands the fixture's inputs to the waterfall and stores the result. Figures from the fixture. */
class AllocationServiceTest {

    private static final GoldenFixture FX = GoldenFixture.shared();
    private static final long INCOME = FX.paise(GoldenKeys.INCOME_MONTHLY);
    private static final long SURPLUS = FX.paise(GoldenKeys.SURPLUS_MONTHLY);
    private static final long EMIS = FX.paise(GoldenKeys.EMI_TOTAL);
    private static final long FIXED = INCOME - SURPLUS - EMIS;
    private static final LocalDate START = LocalDate.of(2026, 10, 1);

    private final UUID user = UUID.randomUUID();
    private AllocationRepository repo;
    private ConsentService consents;
    private LoanService loans;
    private AllocationService service;

    @BeforeEach
    void setUp() {
        repo = mock(AllocationRepository.class);
        consents = mock(ConsentService.class);
        loans = mock(LoanService.class);
        PlanCycleService cycles = mock(PlanCycleService.class);
        IncomeService income = mock(IncomeService.class);
        RecurringService recurring = mock(RecurringService.class);
        GoalService goals = mock(GoalService.class);
        EmergencyFundService ef = mock(EmergencyFundService.class);
        when(cycles.current(user)).thenReturn(new PlanCycleView(START, START.plusMonths(1), 1, "OPEN", null, null, 0, 30));
        when(income.cycleTotal(user)).thenReturn(new CycleTotalView(START, START.plusMonths(1), INCOME, List.of()));
        when(loans.guard(user)).thenReturn(new GuardView("0", "OK", 0L, EMIS, INCOME));
        when(loans.debtTargets(user)).thenReturn(List.of());
        when(recurring.list(user)).thenReturn(List.of(new TemplateView(UUID.randomUUID(), "RENT", 1L,
                com.finpath.rules.RecurringSplit.Frequency.MONTHLY, "UPI", "NEED", START, null, List.of(), FIXED)));
        when(goals.goalTargets(user)).thenReturn(List.of());
        when(ef.size(user, null)).thenReturn(new EmergencyFundView(EMIS + FIXED, EMIS + FIXED, 3,
                3 * (EMIS + FIXED), 3 * (EMIS + FIXED), "3.00", 0L));
        when(repo.save(eq(user), eq(START), anyLong(), anyString())).thenReturn(true);
        service = new AllocationService(cycles, income, loans, recurring, goals, ef, repo, consents,
                RuleConfig.defaults(), new ObjectMapper());
    }

    @Test
    void surplusMatchesTheFixtureAndLinesSumExactlyToIt() {
        AllocationView v = service.run(user, null);
        GoldenAssert.assertWithinOnePercent(SURPLUS, v.surplusPaise());
        assertThat(v.shortfallPaise()).isZero();
        assertThat(v.lines().stream().mapToLong(AllocationService.Line::amountPaise).sum()).isEqualTo(v.surplusPaise());
        assertThat(v.allocatedPaise()).isEqualTo(v.surplusPaise());
        assertThat(v.prepayShare()).isEqualTo(RuleConfig.DEFAULT_PREPAY_SHARE.toPlainString());
    }

    @Test
    void rerunningStoresTheSameResult() {
        service.run(user, null);
        service.run(user, null);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> surplus = ArgumentCaptor.forClass(Long.class);
        verify(repo, times(2)).save(eq(user), eq(START), surplus.capture(), json.capture());
        assertThat(json.getAllValues().get(0)).isEqualTo(json.getAllValues().get(1));
        assertThat(surplus.getAllValues().get(0)).isEqualTo(surplus.getAllValues().get(1));
    }

    @Test
    void prepayShareIsConfigurableAndValidated() {
        when(loans.debtTargets(user)).thenReturn(List.of(new WaterfallAllocator.DebtTarget("d1",
                new BigDecimal("0.09"), false, Money.ofPaise(INCOME * 10))));
        long dflt = prepaid(service.run(user, null));
        long all = prepaid(service.run(user, "1"));
        long none = prepaid(service.run(user, "0"));
        assertThat(none).isZero();
        assertThat(dflt).isPositive().isLessThan(all);
        assertThatThrownBy(() -> service.run(user, "1.5")).isInstanceOf(InvalidAllocationException.class);
        assertThatThrownBy(() -> service.run(user, "-0.1")).isInstanceOf(InvalidAllocationException.class);
        assertThatThrownBy(() -> service.run(user, "abc")).isInstanceOf(InvalidAllocationException.class);
    }

    private static long prepaid(AllocationView v) {
        return v.lines().stream().filter(l -> "DEBT_PREPAYMENT".equals(l.step()))
                .mapToLong(AllocationService.Line::amountPaise).sum();
    }

    @Test
    void shortfallStoresNoLinesAndConsentIsChecked() {
        when(loans.guard(user)).thenReturn(new GuardView("0", "OK", 0L, INCOME + 100L, INCOME));
        AllocationView v = service.run(user, null);
        assertThat(v.lines()).isEmpty();
        assertThat(v.shortfallPaise()).isPositive();
        verify(consents, times(1)).require(user);
    }

    @Test
    void noOpenCycleRowIs404AndNothingStoredOnBadInput() {
        when(repo.save(eq(user), eq(START), anyLong(), anyString())).thenReturn(false);
        assertThatThrownBy(() -> service.run(user, null)).isInstanceOf(AllocationNotFoundException.class);
        assertThatThrownBy(() -> service.run(user, "x")).isInstanceOf(InvalidAllocationException.class);
        verify(repo, times(1)).save(eq(user), eq(START), anyLong(), anyString());
        verify(repo, never()).open(user);
    }

    @Test
    void currentReadsTheStoredRun() {
        when(repo.open(user)).thenReturn(java.util.Optional.of(new AllocationRepository.Stored(START, 100L,
                "[{\"step\":\"LONG_TERM_SIP\",\"ref\":null,\"amountPaise\":100}]")));
        AllocationView v = service.current(user);
        assertThat(v.surplusPaise()).isEqualTo(100L);
        assertThat(v.allocatedPaise()).isEqualTo(100L);
        when(repo.open(user)).thenReturn(java.util.Optional.of(new AllocationRepository.Stored(START, null, "[]")));
        assertThatThrownBy(() -> service.current(user)).isInstanceOf(AllocationNotFoundException.class);
    }
}
