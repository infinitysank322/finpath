package com.finpath.expense;

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
import com.finpath.expense.ExpenseService.CreateResult;
import com.finpath.expense.ExpenseService.ExpenseInput;
import com.finpath.expense.ExpenseService.ExpenseView;
import com.finpath.expense.ExpenseService.ListResult;
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

/** HTTP contract of /expenses: principal-only identity, idempotency statuses, fixed error codes, integer paise. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class ExpenseControllerTest {

    private static final String KEY = "key-0123456789";

    @Autowired MockMvc mvc;
    @Autowired JwtIssuer issuer;
    @MockitoBean ExpenseService service;

    private String bearer(UUID user) {
        return "Bearer " + issuer.issue(user).value();
    }

    private static String body(String extra) {
        return "{\"txnDate\":\"2026-10-22\",\"amountPaise\":4000,\"categoryCode\":\"DINING\",\"mode\":\"UPI\","
                + "\"note\":\"chai\"" + extra + "}";
    }

    private static ExpenseView view(UUID id, boolean dup) {
        return new ExpenseView(id, LocalDate.of(2026, 10, 22), 4000, "DINING", "UPI", null, "MANUAL", "chai", dup);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, UUID user, String content) {
        return b.header("Authorization", bearer(user)).contentType(MediaType.APPLICATION_JSON).content(content);
    }

    @Test
    void everyRouteNeedsAToken() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(get("/expenses")).andExpect(status().isUnauthorized());
        mvc.perform(post("/expenses").contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isUnauthorized());
        mvc.perform(put("/expenses/" + id).contentType(MediaType.APPLICATION_JSON).content(body("")))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/expenses/" + id)).andExpect(status().isUnauthorized());
    }

    @Test
    void createUsesThePrincipalIgnoresBodyUserIdPassesTheKeyAndReturns201() throws Exception {
        UUID me = UUID.randomUUID();
        UUID victim = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(service.create(eq(me), eq(KEY), any())).thenReturn(new CreateResult(view(id, true), true));
        mvc.perform(json(post("/expenses"), me, body(",\"userId\":\"" + victim + "\"")).header("Idempotency-Key", KEY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.amountPaise").value(4000))
                .andExpect(jsonPath("$.possibleDuplicate").value(true))
                .andExpect(jsonPath("$.dedupeHash").doesNotExist());
        verify(service).create(eq(me), eq(KEY), eq(new ExpenseInput(LocalDate.of(2026, 10, 22), 4000L, "DINING",
                "UPI", null, "chai")));
        verify(service, never()).create(eq(victim), any(), any());
    }

    @Test
    void replayIs200AndAMissingKeyIsPassedAsNull() throws Exception {
        UUID me = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(service.create(eq(me), eq(KEY), any())).thenReturn(new CreateResult(view(id, false), false));
        when(service.create(eq(me), eq(null), any())).thenReturn(new CreateResult(view(id, false), true));
        mvc.perform(json(post("/expenses"), me, body("")).header("Idempotency-Key", KEY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(id.toString()));
        mvc.perform(json(post("/expenses"), me, body(""))).andExpect(status().isCreated());
        verify(service).create(eq(me), eq(null), any());
    }

    @Test
    void listPutAndDelete() throws Exception {
        UUID me = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(service.list(me, null, null)).thenReturn(new ListResult(LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 10, 25), List.of(view(id, false))));
        when(service.list(me, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)))
                .thenReturn(new ListResult(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), List.of()));
        when(service.replace(eq(me), eq(id), any())).thenReturn(view(id, false));

        mvc.perform(get("/expenses").header("Authorization", bearer(me))).andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-09-25"))
                .andExpect(jsonPath("$.items[0].id").value(id.toString()))
                .andExpect(jsonPath("$.items[0].amountPaise").value(4000));
        mvc.perform(get("/expenses").param("from", "2026-10-01").param("to", "2026-10-31")
                .header("Authorization", bearer(me))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(json(put("/expenses/" + id), me, body(""))).andExpect(status().isOk());
        mvc.perform(delete("/expenses/" + id).header("Authorization", bearer(me))).andExpect(status().isNoContent());
        verify(service).delete(me, id);
    }

    @Test
    void badInputIsInvalidRequestWithFixedBodyAndNeverEchoesTheNote() throws Exception {
        UUID me = UUID.randomUUID();
        when(service.create(eq(me), any(), any())).thenThrow(new InvalidExpenseException());
        mvc.perform(json(post("/expenses"), me, body("")).header("Idempotency-Key", "x"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.status").value(400)).andExpect(jsonPath("$.length()").value(2));
        String[] bad = {
            "{\"txnDate\":\"2026-10-22\",\"amountPaise\":12.5,\"categoryCode\":\"DINING\",\"mode\":\"UPI\"}",
            "{\"txnDate\":\"22/10/2026\",\"amountPaise\":1,\"categoryCode\":\"DINING\",\"mode\":\"UPI\"}",
            "{\"txnDate\":\"2026-10-22\",\"amountPaise\":\"4000\",\"categoryCode\":[],\"mode\":\"UPI\"}",
            "not json"};
        for (String b : bad) {
            mvc.perform(json(post("/expenses"), me, b)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_request"))
                    .andExpect(jsonPath("$.length()").value(2));
        }
        mvc.perform(get("/expenses").param("from", "not-a-date").header("Authorization", bearer(me)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
    }

    @Test
    void consentRequiredIs403NotFoundIs404ConflictIs409() throws Exception {
        UUID me = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(service.create(eq(me), eq(KEY), any())).thenThrow(new ConsentRequiredException());
        when(service.create(eq(me), eq("key-conflict-1"), any())).thenThrow(new IdempotencyConflictException());
        when(service.replace(eq(me), eq(id), any())).thenThrow(new ExpenseNotFoundException());
        mvc.perform(json(post("/expenses"), me, body("")).header("Idempotency-Key", KEY))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("consent_required"))
                .andExpect(jsonPath("$.length()").value(2));
        mvc.perform(json(post("/expenses"), me, body("")).header("Idempotency-Key", "key-conflict-1"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("idempotency_conflict"))
                .andExpect(jsonPath("$.status").value(409)).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(json(put("/expenses/" + id), me, body(""))).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found")).andExpect(jsonPath("$.length()").value(2));
    }
}
