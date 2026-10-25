package com.finpath.dashboard;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finpath.auth.JwtIssuer;
import com.finpath.dashboard.DashboardService.BaseFi;
import com.finpath.dashboard.DashboardService.BudgetSection;
import com.finpath.dashboard.DashboardService.CycleSection;
import com.finpath.dashboard.DashboardService.DashboardView;
import com.finpath.dashboard.DashboardService.EmiToIncome;
import com.finpath.dashboard.DashboardService.FiSection;
import com.finpath.dashboard.DashboardService.NetWorthSection;
import com.finpath.dashboard.DashboardService.StatsSection;
import com.finpath.dpdp.ConsentRequiredException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract of GET /dashboard with a mocked service: 401, 403, no-store, principal-only identity, integer paise. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class DashboardControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JwtIssuer issuer;
    @MockitoBean DashboardService service;

    private static DashboardView view() {
        LocalDate d = LocalDate.of(2026, 10, 22);
        return new DashboardView(d, new CycleSection(d.withDayOfMonth(1), d.withDayOfMonth(1).plusMonths(1), 31, 21, 10),
                new BudgetSection("OK", 1000L, 400L, 600L, "0.4000", "OK", 60L),
                new NetWorthSection(500L, List.of()),
                new FiSection("OK", List.of(), new BaseFi(12, d.plusYears(12)), null, null, "Educational"),
                new StatsSection("40.0", new EmiToIncome("0.40", "OK"), "50.0", "0.40", null, 3, 500L));
    }

    private String bearer(UUID user) {
        return "Bearer " + issuer.issue(user).value();
    }

    @Test
    void needsAToken() throws Exception {
        mvc.perform(get("/dashboard")).andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTheViewNoStoreWithIntegerPaiseForThePrincipalOnly() throws Exception {
        UUID me = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        when(service.dashboard(me)).thenReturn(view());
        mvc.perform(get("/dashboard").param("userId", victim.toString()).header("Authorization", bearer(me)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.asOf").value("2026-10-22"))
                .andExpect(jsonPath("$.budget.spentPaise").value(400))
                .andExpect(jsonPath("$.netWorth.nowPaise").value(500))
                .andExpect(jsonPath("$.fi.deltaYearsVsLastCycle").doesNotExist())
                .andExpect(jsonPath("$.fi.base.fiYears").value(12))
                .andExpect(jsonPath("$.stats.emiToIncome.status").value("OK"));
        verify(service).dashboard(me);
        verify(service, never()).dashboard(victim);
    }

    @Test
    void noConsentIsAFixedForbidden() throws Exception {
        UUID me = UUID.randomUUID();
        when(service.dashboard(me)).thenThrow(new ConsentRequiredException());
        mvc.perform(get("/dashboard").header("Authorization", bearer(me))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("consent_required")).andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void anUnexpectedFailureIsAFiveHundredNotAnEmptyDashboard() throws Exception {
        UUID me = UUID.randomUUID();
        when(service.dashboard(any())).thenThrow(new IllegalStateException("boom"));
        mvc.perform(get("/dashboard").header("Authorization", bearer(me))).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("server_error"));
    }
}
