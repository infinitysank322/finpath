package com.finpath.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finpath.dpdp.ConsentService;
import com.finpath.income.IncomeService;
import com.finpath.income.IncomeService.CycleTotalView;
import com.finpath.loan.LoanRepository.Draft;
import com.finpath.loan.LoanRepository.Row;
import com.finpath.loan.LoanService.LoanInput;
import com.finpath.rules.EmiGuard;
import com.finpath.rules.Money;
import com.finpath.rules.RuleConfig;
import com.finpath.rules.golden.GoldenFixture;
import com.finpath.rules.golden.GoldenKeys;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T54: the guard runs on loan create; BLOCK refuses the write unless confirmed. Figures from the fixture. */
class DebtTrapGuardTest {

    private static final GoldenFixture FX = GoldenFixture.shared();
    private static final long INCOME = FX.paise(GoldenKeys.INCOME_MONTHLY);
    private static final long EXISTING = FX.paise(GoldenKeys.EMI_TOTAL);
    private static final RuleConfig CFG = RuleConfig.defaults();
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    private final UUID user = UUID.randomUUID();
    private LoanRepository repo;
    private ConsentService consents;
    private LoanService service;

    @BeforeEach
    void setUp() {
        repo = mock(LoanRepository.class);
        consents = mock(ConsentService.class);
        IncomeService income = mock(IncomeService.class);
        when(income.cycleTotal(user)).thenReturn(new CycleTotalView(TODAY, TODAY.plusMonths(1), INCOME, List.of()));
        when(repo.list(user)).thenReturn(List.of(row(EXISTING)));
        service = new LoanService(repo, consents, income, CFG,
                java.time.Clock.fixed(TODAY.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant(),
                        java.time.ZoneId.of("Asia/Kolkata")));
    }

    private static Row row(long emi) {
        return new Row(UUID.randomUUID(), "Bank", "emi", 1_000_000L, new BigDecimal("0.09"), emi, false, 12,
                null, 0L, Instant.EPOCH, null);
    }

    private static long headroom() {
        return EmiGuard.evaluate(Money.ofPaise(EXISTING), Money.ofPaise(INCOME), CFG).headroom().paise();
    }

    private static LoanInput loan(long emi) {
        return new LoanInput("New Bank", "emi", 1_000_000L, "0.09", emi, 12L, null, null, null);
    }

    @Test
    void guardEndpointMatchesRulesOutput() {
        LoanService.GuardView g = service.guard(user);
        EmiGuard.Result r = EmiGuard.evaluate(Money.ofPaise(EXISTING), Money.ofPaise(INCOME), CFG);
        assertThat(new BigDecimal(g.ratio())).isEqualByComparingTo(r.ratio());
        assertThat(g.status()).isEqualTo(r.status().name());
        assertThat(g.headroomPaise()).isEqualTo(r.headroom().paise());
        assertThat(g.totalEmiPaise()).isEqualTo(EXISTING);
        assertThat(g.monthlyIncomePaise()).isEqualTo(INCOME);
    }

    @Test
    void loanPushingPastBlockIsRefusedAndNotPersisted() {
        long tripping = headroom() + 1;
        assertThatThrownBy(() -> service.create(user, loan(tripping), false))
                .isInstanceOfSatisfying(DebtTrapBlockedException.class, e -> {
                    EmiGuard.Result r = EmiGuard.evaluate(Money.ofPaise(EXISTING + tripping),
                            Money.ofPaise(INCOME), CFG);
                    assertThat(e.guard().status()).isEqualTo("BLOCK");
                    assertThat(new BigDecimal(e.guard().ratio())).isEqualByComparingTo(r.ratio());
                    assertThat(e.guard().totalEmiPaise()).isEqualTo(EXISTING + tripping);
                });
        verify(repo, never()).insert(any(), any());
    }

    @Test
    void explicitConfirmationPersistsABlockedLoan() {
        long tripping = headroom() + 1;
        when(repo.insert(any(), any())).thenAnswer(i -> {
            Draft d = i.getArgument(1);
            return row(d.emiPaise());
        });
        assertThat(service.create(user, loan(tripping), true).emiPaise()).isEqualTo(tripping);
        verify(repo).insert(any(), any());
    }

    @Test
    void loanExactlyAtBlockThresholdIsWarnAndPersists() {
        long atLimit = headroom();
        when(repo.insert(any(), any())).thenAnswer(i -> row(((Draft) i.getArgument(1)).emiPaise()));
        assertThat(service.create(user, loan(atLimit), false)).isNotNull();
    }

    @Test
    void invalidLoanIsRejectedBeforeTheGuardOrConsent() {
        assertThatThrownBy(() -> service.create(user, new LoanInput("", "emi", 1L, "0.09", 1L, 12L, null, null, null),
                false)).isInstanceOf(InvalidLoanException.class);
        verify(repo, never()).insert(any(), any());
    }

    private org.springframework.test.web.servlet.MockMvc mvc() {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new LoanController(service)).setControllerAdvice(new LoanExceptionHandler())
                .setCustomArgumentResolvers(new com.finpath.auth.CurrentUserIdArgumentResolver()).build();
    }

    private void authenticate() {
        org.springframework.security.oauth2.jwt.Jwt jwt = org.springframework.security.oauth2.jwt.Jwt
                .withTokenValue("t").header("alg", "none").subject(user.toString()).build();
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken(jwt,
                        List.of()));
    }

    @Test
    void httpBlockedIs409WithGuardBodyAndConfirmBlockPersists() throws Exception {
        authenticate();
        try {
            String json = "{\"lender\":\"New Bank\",\"type\":\"emi\",\"principalOutstandingPaise\":1000000,"
                    + "\"rate\":\"0.09\",\"emiPaise\":" + (headroom() + 1) + ",\"emisLeft\":12}";
            var post = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/loans")
                    .contentType("application/json").content(json);
            mvc().perform(post).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .status().isConflict())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.error").value("debt_trap_blocked"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                            .jsonPath("$.guard.status").value("BLOCK"));
            verify(repo, never()).insert(any(), any());
            when(repo.insert(any(), any())).thenAnswer(i -> row(((Draft) i.getArgument(1)).emiPaise()));
            mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/loans")
                    .param("confirmBlock", "true").contentType("application/json").content(json))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                            .isCreated());
            mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/loans/debt-trap-guard")).andExpect(org.springframework.test.web.servlet.result
                            .MockMvcResultMatchers.jsonPath("$.totalEmiPaise").value(EXISTING));
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void missingUserRowIs404NotServerError() throws Exception {
        IncomeService income = mock(IncomeService.class);
        when(income.cycleTotal(user)).thenThrow(new com.finpath.income.IncomeNotFoundException());
        service = new LoanService(repo, consents, income, CFG);
        authenticate();
        try {
            mvc().perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .get("/loans/debt-trap-guard")).andExpect(org.springframework.test.web.servlet.result
                            .MockMvcResultMatchers.status().isNotFound());
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test
    void closedLoansDoNotCountTowardsTheRatio() {
        Row closed = new Row(UUID.randomUUID(), "Old", "emi", 0L, new BigDecimal("0.09"), 999_999_999L, false, 1,
                null, 0L, Instant.EPOCH, null);
        when(repo.list(user)).thenReturn(List.of(row(EXISTING), closed));
        assertThat(service.guard(user).totalEmiPaise()).isEqualTo(EXISTING);
    }
}
