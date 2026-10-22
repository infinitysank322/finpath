package com.finpath.budget;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finpath.auth.JwtIssuer;
import com.finpath.budget.BudgetAlertService.Event;
import com.finpath.budget.BudgetService.BudgetInput;
import com.finpath.budget.BudgetService.Line;
import com.finpath.budget.BudgetService.StatusView;
import com.finpath.dpdp.ConsentRequiredException;
import com.finpath.rules.BudgetRules.Level;
import com.finpath.rules.golden.GoldenFixture;
import com.finpath.rules.golden.GoldenKeys;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** HTTP contract of /budgets: principal-only identity, fixed error codes, integer paise. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class BudgetControllerTest {

    private static final long BUDGET = GoldenFixture.shared().paise(GoldenKeys.BUDGET_CATEGORY);
    private static final long SPENT = GoldenFixture.shared().paise(GoldenKeys.BUDGET_SPENT_WARN);

    @Autowired MockMvc mvc;
    @Autowired JwtIssuer issuer;
    @MockitoBean BudgetService service;
    @MockitoBean BudgetAlertService alerts;

    private String bearer(UUID user) {
        return "Bearer " + issuer.issue(user).value();
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, UUID user, String content) {
        return b.header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(content);
    }

    private static Line line() {
        return new Line("DINING", BUDGET, SPENT, BUDGET - SPENT, "0.8981", Level.WARN, Level.OK);
    }

    @Test
    void everyRouteNeedsAToken() throws Exception {
        mvc.perform(put("/budgets/DINING").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/budgets/DINING")).andExpect(status().isUnauthorized());
        mvc.perform(get("/budgets/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/budgets/evaluate")).andExpect(status().isUnauthorized());
    }

    @Test
    void putUsesThePrincipalIgnoresBodyUserIdAndPassesTheCycleDate() throws Exception {
        UUID me = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        when(service.put(eq(me), eq("DINING"), eq(LocalDate.of(2026, 10, 15)), any())).thenReturn(line());
        mvc.perform(json(put("/budgets/DINING").param("cycleStart", "2026-10-15"), me,
                "{\"budgetPaise\":" + BUDGET + ",\"userId\":\"" + victim + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryCode").value("DINING"))
                .andExpect(jsonPath("$.budgetPaise").value(BUDGET))
                .andExpect(jsonPath("$.spentPaise").value(SPENT))
                .andExpect(jsonPath("$.remainingPaise").value(BUDGET - SPENT))
                .andExpect(jsonPath("$.usedFraction").value("0.8981"))
                .andExpect(jsonPath("$.level").value("WARN"))
                .andExpect(jsonPath("$.lastAlertLevel").value("OK"));
        verify(service).put(eq(me), eq("DINING"), eq(LocalDate.of(2026, 10, 15)), eq(new BudgetInput(BUDGET)));
        verify(service, never()).put(eq(victim), any(), any(), any());
    }

    @Test
    void statusDeleteAndEvaluate() throws Exception {
        UUID me = UUID.randomUUID();
        when(service.status(me, null)).thenReturn(new StatusView(LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 11, 1), List.of(line())));
        when(alerts.evaluate(me, null)).thenReturn(List.of(new Event("DINING", Level.WARN)));
        mvc.perform(get("/budgets/status").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleStart").value("2026-10-01"))
                .andExpect(jsonPath("$.cycleEndExclusive").value("2026-11-01"))
                .andExpect(jsonPath("$.lines[0].categoryCode").value("DINING"));
        mvc.perform(post("/budgets/evaluate").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].categoryCode").value("DINING"))
                .andExpect(jsonPath("$.events[0].level").value("WARN"));
        mvc.perform(delete("/budgets/DINING").header("Authorization", bearer(me)))
                .andExpect(status().isNoContent());
        verify(service).delete(me, "DINING", null);
    }

    @Test
    void errorsUseFixedCodes() throws Exception {
        UUID me = UUID.randomUUID();
        when(service.put(eq(me), eq("NOPE"), any(), any())).thenThrow(new InvalidBudgetException());
        when(service.put(eq(me), eq("FUEL"), any(), any())).thenThrow(new ConsentRequiredException());
        when(alerts.evaluate(me, null)).thenThrow(new ConsentRequiredException());
        org.mockito.Mockito.doThrow(new BudgetNotFoundException()).when(service).delete(me, "FUEL", null);

        mvc.perform(json(put("/budgets/NOPE"), me, "{\"budgetPaise\":1}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(json(put("/budgets/FUEL"), me, "{\"budgetPaise\":1}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("consent_required"));
        mvc.perform(post("/budgets/evaluate").header("Authorization", bearer(me))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("consent_required"));
        mvc.perform(delete("/budgets/FUEL").header("Authorization", bearer(me))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found")).andExpect(jsonPath("$.length()").value(2));
        for (String bad : new String[] {"{\"budgetPaise\":12.5}", "{\"budgetPaise\":\"x\"}", "not json"}) {
            mvc.perform(json(put("/budgets/DINING"), me, bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"));
        }
        mvc.perform(get("/budgets/status").param("cycleStart", "15/10/2026").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
    }
}
