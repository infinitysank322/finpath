package com.finpath.plan;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpath.auth.JwtIssuer;
import com.finpath.dpdp.ConsentRequiredException;
import com.finpath.plan.PlanCycleService.PlanCycleView;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract of /plan-cycles: principal-only identity, fixed error codes, integer paise. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class PlanCycleControllerTest {

    @Autowired MockMvc mvc;
    @Autowired JwtIssuer issuer;
    @Autowired ObjectMapper mapper;
    @MockitoBean PlanCycleService service;

    private String bearer(UUID user) {
        return "Bearer " + issuer.issue(user).value();
    }

    private PlanCycleView view(int day) {
        return new PlanCycleView(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), day, "OPEN", 3983500L,
                mapper.createArrayNode(), 21, 10);
    }

    @Test
    void needsAToken() throws Exception {
        mvc.perform(get("/plan-cycles/current")).andExpect(status().isUnauthorized());
        mvc.perform(put("/plan-cycles/start-day").contentType(MediaType.APPLICATION_JSON).content("{\"startDay\":1}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void currentUsesThePrincipalAndReturnsIntegerPaise() throws Exception {
        UUID me = UUID.randomUUID();
        when(service.current(me)).thenReturn(view(1));
        mvc.perform(get("/plan-cycles/current").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleStart").value("2026-10-01"))
                .andExpect(jsonPath("$.surplusPaise").value(3983500))
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void changeStartDayIgnoresAnyUserIdInTheBody() throws Exception {
        UUID me = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        when(service.changeStartDay(me, 1)).thenReturn(view(1));
        mvc.perform(put("/plan-cycles/start-day").header("Authorization", bearer(me))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"startDay\":1,\"userId\":\"" + victim + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDay").value(1));
        verify(service).changeStartDay(me, 1);
        verify(service, never()).changeStartDay(eq(victim), any());
    }

    @Test
    void badInputIsInvalidRequestWithFixedBody() throws Exception {
        UUID me = UUID.randomUUID();
        when(service.changeStartDay(eq(me), any())).thenThrow(new InvalidStartDayException());
        for (String body : new String[] {"{\"startDay\":0}", "{\"startDay\":32}", "{}", "{\"startDay\":null}"}) {
            mvc.perform(put("/plan-cycles/start-day").header("Authorization", bearer(me))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.status").value(400))
                    .andExpect(jsonPath("$.error").value("invalid_request"))
                    .andExpect(jsonPath("$.length()").value(2));
        }
        for (String body : new String[] {"{\"startDay\":1.5}", "{\"startDay\":\"x\"}", "not json"}) {
            mvc.perform(put("/plan-cycles/start-day").header("Authorization", bearer(me))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"));
        }
    }

    @Test
    void consentRequiredIs403WithFixedCode() throws Exception {
        UUID me = UUID.randomUUID();
        when(service.current(me)).thenThrow(new ConsentRequiredException());
        mvc.perform(get("/plan-cycles/current").header("Authorization", bearer(me)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("consent_required"))
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void missingUserIs404() throws Exception {
        UUID me = UUID.randomUUID();
        when(service.current(me)).thenThrow(new PlanUserNotFoundException());
        mvc.perform(get("/plan-cycles/current").header("Authorization", bearer(me)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }
}
