package com.finpath.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** T32 acceptance: the OpenAPI document is served, without credentials, and describes the real API. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class OpenApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void documentIsServedWithoutCredentials() throws Exception {
        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("FinPath API"))
                .andExpect(jsonPath("$.paths['/me/export']").exists())
                .andExpect(jsonPath("$.paths['/auth/token']").exists())
                .andExpect(jsonPath("$.paths['/income-sources']").exists())
                .andExpect(jsonPath("$.paths['/income-sources/{id}']").exists())
                .andExpect(jsonPath("$.paths['/income-sources/cycle-total']").exists())
                .andExpect(jsonPath("$.paths['/expenses']").exists())
                .andExpect(jsonPath("$.paths['/expenses'].post").exists())
                .andExpect(jsonPath("$.paths['/expenses'].get").exists())
                .andExpect(jsonPath("$.paths['/expenses/{id}'].put").exists())
                .andExpect(jsonPath("$.paths['/expenses/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/expenses/{id}/tag'].patch").exists())
                .andExpect(jsonPath("$.paths['/expenses/tag-summary'].get").exists())
                .andExpect(jsonPath("$.paths['/expenses'].post.parameters[?(@.name=='Idempotency-Key')]").exists())
                .andExpect(jsonPath("$.paths['/recurring-templates'].post").exists())
                .andExpect(jsonPath("$.paths['/recurring-templates'].get").exists())
                .andExpect(jsonPath("$.paths['/recurring-templates/{id}'].put").exists())
                .andExpect(jsonPath("$.paths['/recurring-templates/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/recurring-templates/materialise'].post").exists())
                .andExpect(jsonPath("$.paths['/budgets/{categoryCode}'].put").exists())
                .andExpect(jsonPath("$.paths['/budgets/{categoryCode}'].delete").exists())
                .andExpect(jsonPath("$.paths['/budgets/status'].get").exists())
                .andExpect(jsonPath("$.paths['/budgets/evaluate'].post").exists())
                .andExpect(jsonPath("$.paths['/loans'].post").exists())
                .andExpect(jsonPath("$.paths['/loans'].get").exists())
                .andExpect(jsonPath("$.paths['/loans/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/loans/{id}/detail'].get").exists())
                .andExpect(jsonPath("$.paths['/loans/debt-trap-guard'].get").exists())
                .andExpect(jsonPath("$.paths['/loans/{id}'].put").exists())
                .andExpect(jsonPath("$.paths['/loans/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/goals'].post").exists())
                .andExpect(jsonPath("$.paths['/goals'].get").exists())
                .andExpect(jsonPath("$.paths['/goals/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/goals/{id}/status'].get").exists())
                .andExpect(jsonPath("$.paths['/goals/{id}'].put").exists())
                .andExpect(jsonPath("$.paths['/goals/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/holdings'].post").exists())
                .andExpect(jsonPath("$.paths['/holdings'].get").exists())
                .andExpect(jsonPath("$.paths['/holdings/aggregate'].get").exists())
                .andExpect(jsonPath("$.paths['/holdings/{id}'].get").exists())
                .andExpect(jsonPath("$.paths['/holdings/{id}'].put").exists())
                .andExpect(jsonPath("$.paths['/holdings/{id}'].delete").exists())
                .andExpect(jsonPath("$.paths['/net-worth'].get").exists())
                .andExpect(jsonPath("$.paths['/checklist/generate'].post").exists())
                .andExpect(jsonPath("$.paths['/checklist'].get").exists())
                .andExpect(jsonPath("$.paths['/checklist/items/{id}/done'].post").exists())
                .andExpect(jsonPath("$.paths['/checklist/streak'].get").exists())
                .andExpect(jsonPath("$.paths['/plan/rebalance'].post").exists())
                .andExpect(jsonPath("$.components.securitySchemes.bearer.scheme").value("bearer"));
    }

    @Test
    void loanSchemasCarryTheOptionalIntegerDueDay() throws Exception {
        JsonNode schemas = mapper.readTree(mvc.perform(get("/v3/api-docs")).andReturn().getResponse()
                .getContentAsString()).at("/components/schemas");
        for (String name : List.of("LoanInput", "LoanView", "LoanDetailView")) {
            JsonNode dueDay = schemas.at("/" + name + "/properties/dueDay");
            assertThat(dueDay.isMissingNode()).as(name + ".dueDay").isFalse();
            assertThat(dueDay.get("type").asText()).as(name + ".dueDay type").isEqualTo("integer");
            assertThat(schemas.at("/" + name + "/required").findValuesAsText("")).doesNotContain("dueDay");
        }
    }

    @Test
    void noSchemaDeclaresAFloatingPointNumber() throws Exception {
        String doc = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        JsonNode root = mapper.readTree(doc);
        assertThat(root.findValues("format").stream().map(JsonNode::asText))
                .as("money is integer paise; no float/double in the API surface")
                .doesNotContain("float", "double");
    }

    @Test
    void otherUnauthenticatedPathsStayClosed() throws Exception {
        mvc.perform(get("/me/export")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
    }
}
