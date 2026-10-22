package com.finpath.income;

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
import com.finpath.dpdp.ConsentRequiredException;
import com.finpath.income.IncomeService.CycleItem;
import com.finpath.income.IncomeService.CycleTotalView;
import com.finpath.income.IncomeService.IncomeInput;
import com.finpath.income.IncomeService.IncomeView;
import com.finpath.rules.IncomeCalculator.Frequency;
import com.finpath.rules.IncomeCalculator.Type;
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

/** HTTP contract of /income-sources: principal-only identity, fixed error codes, integer paise. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class IncomeControllerTest {

    private static final long SALARY = GoldenFixture.shared().paise(GoldenKeys.INCOME_SALARY);

    @Autowired MockMvc mvc;
    @Autowired JwtIssuer issuer;
    @MockitoBean IncomeService service;

    private String bearer(UUID user) {
        return "Bearer " + issuer.issue(user).value();
    }

    private static String body(String extra) {
        return "{\"type\":\"SALARY\",\"frequency\":\"MONTHLY\",\"amountPaise\":" + SALARY
                + ",\"startDate\":\"2026-01-01\"" + extra + "}";
    }

    private static IncomeView view(UUID id) {
        return new IncomeView(id, Type.SALARY, Frequency.MONTHLY, SALARY, LocalDate.of(2026, 1, 1), null, true);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, UUID user, String content) {
        return b.header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(content);
    }

    @Test
    void everyRouteNeedsAToken() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/income-sources")).andExpect(status().isUnauthorized());
        mvc.perform(get("/income-sources/cycle-total")).andExpect(status().isUnauthorized());
        mvc.perform(post("/income-sources").contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/income-sources/" + id).contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/income-sources/" + id)).andExpect(status().isUnauthorized());
    }

    @Test
    void createUsesThePrincipalIgnoresBodyUserIdAndReturns201WithIntegerPaise() throws Exception {
        UUID me = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(service.create(eq(me), any())).thenReturn(view(id));
        mvc.perform(json(post("/income-sources"), me, body(",\"userId\":\"" + victim + "\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.amountPaise").value(SALARY))
                .andExpect(jsonPath("$.startDate").value("2026-01-01"))
                .andExpect(jsonPath("$.countsInCurrentCycle").value(true));
        verify(service).create(eq(me), eq(new IncomeInput(Type.SALARY, Frequency.MONTHLY, SALARY,
                LocalDate.of(2026, 1, 1), null)));
        verify(service, never()).create(eq(victim), any());
    }

    @Test
    void listPutDeleteAndCycleTotal() throws Exception {
        UUID me = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(service.list(me)).thenReturn(List.of(view(id)));
        when(service.replace(eq(me), eq(id), any())).thenReturn(view(id));
        when(service.cycleTotal(me)).thenReturn(new CycleTotalView(LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 11, 1), SALARY, List.of(new CycleItem(id, Type.SALARY, SALARY))));

        mvc.perform(get("/income-sources").header("Authorization", bearer(me)))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id.toString()));
        mvc.perform(json(put("/income-sources/" + id), me, body(""))).andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("SALARY"));
        mvc.perform(get("/income-sources/cycle-total").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycleStart").value("2026-10-01"))
                .andExpect(jsonPath("$.cycleEndExclusive").value("2026-11-01"))
                .andExpect(jsonPath("$.totalPaise").value(SALARY))
                .andExpect(jsonPath("$.items[0].amountPaise").value(SALARY));
        mvc.perform(delete("/income-sources/" + id).header("Authorization", bearer(me)))
                .andExpect(status().isNoContent());
        verify(service).delete(me, id);
    }

    @Test
    void badBodiesAreInvalidRequestWithFixedBody() throws Exception {
        UUID me = UUID.randomUUID();
        when(service.create(eq(me), any())).thenThrow(new InvalidIncomeException());
        mvc.perform(json(post("/income-sources"), me, body(",\"endDate\":\"2025-12-31\"")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.length()").value(2));
        String[] bad = {
            "{\"type\":\"LOTTERY\",\"frequency\":\"MONTHLY\",\"amountPaise\":1,\"startDate\":\"2026-01-01\"}",
            "{\"type\":\"SALARY\",\"frequency\":\"WEEKLY\",\"amountPaise\":1,\"startDate\":\"2026-01-01\"}",
            "{\"type\":\"SALARY\",\"frequency\":\"MONTHLY\",\"amountPaise\":12.5,\"startDate\":\"2026-01-01\"}",
            "{\"type\":\"SALARY\",\"frequency\":\"MONTHLY\",\"amountPaise\":1,\"startDate\":\"01/02/2026\"}",
            "{\"type\":\"SALARY\",\"frequency\":\"MONTHLY\",\"amountPaise\":1,\"startDate\":\"2026-02-30\"}",
            "not json"};
        for (String b : bad) {
            mvc.perform(json(post("/income-sources"), me, b)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"))
                    .andExpect(jsonPath("$.length()").value(2));
        }
    }

    @Test
    void consentRequiredIs403AndUnknownIdIs404() throws Exception {
        UUID me = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(service.create(eq(me), any())).thenThrow(new ConsentRequiredException());
        when(service.replace(eq(me), eq(id), any())).thenThrow(new IncomeNotFoundException());
        mvc.perform(json(post("/income-sources"), me, body(""))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("consent_required")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(json(put("/income-sources/" + id), me, body(""))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found")).andExpect(jsonPath("$.length()").value(2));
    }
}
