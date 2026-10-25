package com.finpath.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finpath.dpdp.ConsentService;
import com.finpath.income.IncomeService;
import com.finpath.loan.LoanRepository.Row;
import com.finpath.loan.LoanService.PayoffPlanView;
import com.finpath.rules.RuleConfig;
import com.finpath.rules.golden.GoldenFixture;
import com.finpath.rules.golden.GoldenKeys;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T74: debt-free cycle is the latest payoff cycle of the avalanche plan; absent when there is no open debt. */
class LoanDebtFreeTest {

    private static final GoldenFixture FX = GoldenFixture.shared();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final UUID user = UUID.randomUUID();
    private LoanRepository repo;
    private LoanService service;

    @BeforeEach
    void setUp() {
        repo = mock(LoanRepository.class);
        when(repo.cycleStartDay(user)).thenReturn(Optional.of(1));
        service = new LoanService(repo, mock(ConsentService.class), mock(IncomeService.class),
                RuleConfig.defaults(), Clock.fixed(TODAY.atStartOfDay(IST).toInstant(), IST));
    }

    private Row home(long principal) {
        return new Row(UUID.randomUUID(), "Bank", "emi", principal,
                new BigDecimal(FX.text(GoldenKeys.LOAN_HOME_RATE)), FX.paise(GoldenKeys.LOAN_HOME_EMI_STATED), false,
                FX.integer(GoldenKeys.LOAN_HOME_EMIS_LEFT), null, 0L, Instant.EPOCH);
    }

    @Test
    void emptyWithNoLoans() {
        when(repo.list(user)).thenReturn(List.of());
        assertThat(service.debtFree(user, TODAY)).isNull();
    }

    @Test
    void emptyWhenEveryLoanIsPaidOff() {
        when(repo.list(user)).thenReturn(List.of(home(0L)));
        assertThat(service.debtFree(user, TODAY)).isNull();
    }

    @Test
    void isTheLastStepOfTheAvalanchePlan() {
        when(repo.list(user)).thenReturn(List.of(home(FX.paise(GoldenKeys.LOAN_HOME_PRINCIPAL))));
        PayoffPlanView plan = service.payoffPlan(user, "avalanche");
        var last = plan.order().stream().max(java.util.Comparator.comparingInt(s -> s.payoffCycle())).orElseThrow();
        assertThat(service.debtFree(user, TODAY)).isEqualTo(last.payoffCycleDates());
        assertThat(service.debtFree(user, TODAY).start()).isAfterOrEqualTo(TODAY.withDayOfMonth(1));
    }
}
