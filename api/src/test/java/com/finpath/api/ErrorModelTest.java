package com.finpath.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.finpath.auth.JwtIssuer;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** T32: every failure has the one JSON shape with a fixed code and leaks nothing from the request or exception. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
@ExtendWith(OutputCaptureExtension.class)
@Import(ErrorModelTest.Config.class)
class ErrorModelTest {

    static final String SECRET_TEXT = "SWIGGY BANGALORE Rs 1,234.50 asha@example.com";

    record Payload(String merchant, long amountPaise) {
    }

    @RestController
    static class Boom {
        @GetMapping("/test-boom/npe")
        String npe() {
            throw new IllegalStateException("charge of " + SECRET_TEXT + " 123450 paise failed");
        }

        @GetMapping("/test-boom/denied")
        String denied() {
            throw new AccessDeniedException(SECRET_TEXT);
        }

        @GetMapping("/test-boom/param")
        String param(@RequestParam long amountPaise) {
            return "ok";
        }

        @PostMapping("/test-boom/body")
        String body(@RequestBody Payload p) {
            return "ok";
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        Boom boom() {
            return new Boom();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JwtIssuer issuer;

    private String bearer() {
        return "Bearer " + issuer.issue(UUID.randomUUID()).value();
    }

    @Test
    void a500HasAGenericBodyWithNoAmountMerchantEmailOrStackTrace(CapturedOutput output) throws Exception {
        MvcResult result = mvc.perform(get("/test-boom/npe").header("Authorization", bearer()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"status\":500,\"error\":\"server_error\"}", true))
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("SWIGGY").doesNotContain("1,234").doesNotContain("123450")
                .doesNotContain("asha").doesNotContain("Rs").doesNotContain("IllegalState")
                .doesNotContain("at com.finpath").doesNotContain("trace").doesNotContain("message");
        assertThat(output.getAll()).contains("IllegalStateException")
                .doesNotContain("SWIGGY").doesNotContain("1,234").doesNotContain("asha@example.com")
                .doesNotContain("\tat ");
    }

    @Test
    void securityExceptionsFromControllersStayA403NotA500() throws Exception {
        mvc.perform(get("/test-boom/denied").header("Authorization", bearer()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("forbidden"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SWIGGY"))));
    }

    @Test
    void unauthenticatedIsA401WithTheSameShape() throws Exception {
        mvc.perform(get("/test-boom/npe")).andExpect(status().isUnauthorized())
                .andExpect(content().json("{\"status\":401,\"error\":\"unauthorized\"}", true));
    }

    @Test
    void badRequestsAre400WithoutEchoingTheValue() throws Exception {
        mvc.perform(get("/test-boom/param").param("amountPaise", "12.50 SWIGGY").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"status\":400,\"error\":\"invalid_request\"}", true));
        mvc.perform(get("/test-boom/param").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"status\":400,\"error\":\"invalid_request\"}", true));
        mvc.perform(post("/test-boom/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchant\":\"SWIGGY\",\"amountPaise\":12.5x").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"status\":400,\"error\":\"invalid_request\"}", true));
    }

    @Test
    void floatAmountForAPaiseFieldIsRejectedNotRounded() throws Exception {
        // Jackson would coerce 12.5 to 12 for a long by default; the contract says paise are integers.
        mvc.perform(post("/test-boom/body").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchant\":\"x\",\"amountPaise\":12.5}").header("Authorization", bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(content().json("{\"status\":400,\"error\":\"invalid_request\"}", true));
    }

    @Test
    void unknownRouteWrongMethodAndWrongMediaTypeHaveFixedCodes() throws Exception {
        mvc.perform(get("/no-such-route").header("Authorization", bearer())).andExpect(status().isNotFound())
                .andExpect(content().json("{\"status\":404,\"error\":\"not_found\"}", true));
        mvc.perform(post("/test-boom/npe").header("Authorization", bearer())).andExpect(status().isMethodNotAllowed())
                .andExpect(content().json("{\"status\":405,\"error\":\"method_not_allowed\"}", true));
        mvc.perform(post("/test-boom/body").contentType(MediaType.TEXT_PLAIN).content("SWIGGY")
                        .header("Authorization", bearer()))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().json("{\"status\":415,\"error\":\"unsupported_media_type\"}", true));
    }
}
