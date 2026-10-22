package com.finpath.rebalance;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finpath.auth.JwtIssuer;
import com.finpath.dpdp.ConsentRequiredException;
import com.finpath.rebalance.RebalanceService.Line;
import com.finpath.rebalance.RebalanceService.Preview;
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

/** HTTP contract of POST /plan/rebalance with a mocked service: principal-only identity, fixed errors, integer paise. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class RebalanceControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JwtIssuer issuer;
    @MockitoBean RebalanceService service;

    private MockHttpServletRequestBuilder req(UUID user, String body) {
        return post("/plan/rebalance").header("Authorization", "Bearer " + issuer.issue(user).value())
                .contentType(MediaType.APPLICATION_JSON).content(body);
    }

    @Test
    void needsAToken() throws Exception {
        mvc.perform(post("/plan/rebalance").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void returnsTheServicePreviewUsingThePrincipalOnly() throws Exception {
        UUID me = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        when(service.preview(me, 1234L)).thenReturn(new Preview(List.of(new Line("LONG_TERM_SIP", null, 1000L),
                new Line("HABIT_SIP", null, 234L)), "LONG_TERM_SIP", 0L, 77L,
                List.of(new Line("HIGH_INTEREST_DEBT", "d1", 5L)), 900L, 800L));
        mvc.perform(req(me, "{\"amountPaise\":1234,\"userId\":\"" + victim + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.primaryAbsorbingStep").value("LONG_TERM_SIP"))
                .andExpect(jsonPath("$.absorbed[0].amountPaise").value(1000))
                .andExpect(jsonPath("$.absorbed[1].step").value("HABIT_SIP"))
                .andExpect(jsonPath("$.uncoveredPaise").value(0))
                .andExpect(jsonPath("$.surplusAfterPaise").value(77))
                .andExpect(jsonPath("$.lines[0].ref").value("d1"))
                .andExpect(jsonPath("$.safeToSpendBeforePaise").value(900))
                .andExpect(jsonPath("$.safeToSpendAfterPaise").value(800));
        verify(service, never()).preview(eq(victim), any());
    }

    @Test
    void corruptStoredPlanIsAFixed500WithoutTheMessage() throws Exception {
        UUID me = UUID.randomUUID();
        when(service.preview(me, 9L)).thenThrow(new IllegalStateException("secret-detail"));
        when(service.preview(me, 8L)).thenThrow(new ArithmeticException("secret-detail"));
        for (String amount : new String[] {"9", "8"}) {
            mvc.perform(req(me, "{\"amountPaise\":" + amount + "}")).andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.error").value("server_error"))
                    .andExpect(jsonPath("$.length()").value(2));
        }
    }

    @Test
    void errorsUseFixedCodes() throws Exception {
        UUID me = UUID.randomUUID();
        when(service.preview(me, 1L)).thenThrow(new RebalanceNotFoundException());
        when(service.preview(me, 2L)).thenThrow(new ConsentRequiredException());
        when(service.preview(me, 3L)).thenThrow(new InvalidRebalanceException());
        mvc.perform(req(me, "{\"amountPaise\":1}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(req(me, "{\"amountPaise\":2}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("consent_required"));
        mvc.perform(req(me, "{\"amountPaise\":3}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"));
        for (String bad : new String[] {"{\"amountPaise\":12.5}", "{\"amountPaise\":\"x\"}", "not json", ""}) {
            mvc.perform(req(me, bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"));
        }
    }
}
