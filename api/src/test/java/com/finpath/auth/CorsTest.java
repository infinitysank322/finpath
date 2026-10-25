package com.finpath.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * T33 acceptance: the API chain answers CORS for exact allowed origins only, before authentication,
 * without credentials; the sign-in chain does no CORS at all. DB-independent (ci profile).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class CorsTest {

    private static final String ALLOWED = "http://localhost:4200";
    private static final String EVIL = "https://evil.example";

    @Autowired
    private MockMvc mvc;

    @Test
    void preflightFromAllowedOriginWithoutTokenIs200WithCorsHeaders() throws Exception {
        mvc.perform(options("/expenses")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type,idempotency-key"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
                .andExpect(result -> {
                    String methods = result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS);
                    assertThat(Arrays.stream(methods.split(",")).map(String::trim))
                            .contains("GET", "POST", "PUT", "PATCH", "DELETE");
                    String headers = result.getResponse().getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS);
                    assertThat(headers.toLowerCase()).contains("authorization", "content-type", "idempotency-key");
                });
    }

    @Test
    void preflightFromDisallowedOriginIs403WithoutAcao() throws Exception {
        mvc.perform(options("/expenses")
                        .header(HttpHeaders.ORIGIN, EVIL)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void preflightAskingForAnUnlistedHeaderIsRefused() throws Exception {
        mvc.perform(options("/expenses")
                        .header(HttpHeaders.ORIGIN, ALLOWED)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "cookie"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void lookalikeOriginsAreNotAllowed() throws Exception {
        for (String origin : List.of("http://localhost:4201", "https://localhost:4200",
                "http://localhost:4200.evil.example", "null")) {
            mvc.perform(options("/expenses")
                            .header(HttpHeaders.ORIGIN, origin)
                            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        }
    }

    @Test
    void unauthenticatedRequestFromAllowedOriginIsJson401CarryingAcao() throws Exception {
        mvc.perform(get("/expenses").header(HttpHeaders.ORIGIN, ALLOWED))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.error").value("unauthorized"));
    }

    @Test
    void tokenEndpointsFromAllowedOriginCarryAcao() throws Exception {
        mvc.perform(post("/auth/token").header(HttpHeaders.ORIGIN, ALLOWED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"not-a-code\",\"codeVerifier\":\"not-a-verifier\"}"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED));
        mvc.perform(post("/auth/refresh").header(HttpHeaders.ORIGIN, ALLOWED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"not-a-token\"}"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED));
    }

    @Test
    void tokenEndpointsFromDisallowedOriginAreRefused() throws Exception {
        mvc.perform(post("/auth/refresh").header(HttpHeaders.ORIGIN, EVIL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"not-a-token\"}"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void signInChainDoesNoCors() throws Exception {
        mvc.perform(get("/oauth2/authorization/google").header(HttpHeaders.ORIGIN, ALLOWED))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    // --- startup validation of FINPATH_CORS_ALLOWED_ORIGINS ---

    @Test
    void wildcardOriginFailsStartup() {
        assertThatThrownBy(() -> new CorsProperties(List.of("*")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(CorsProperties.ENV);
        assertThatThrownBy(() -> new CorsProperties(List.of(ALLOWED, "*")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CorsProperties(List.of("https://*.example.com")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankEntryInAListFailsStartup() {
        assertThatThrownBy(() -> new CorsProperties(List.of(ALLOWED, " ")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank");
        assertThatThrownBy(() -> new CorsProperties(List.of("", ALLOWED)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void entriesThatAreNotBareOriginsFailStartup() {
        for (String bad : List.of("null", "localhost:4200", "http://localhost:4200/", "http://localhost:4200/app",
                "http://localhost:4200?x=1", "http://u@localhost:4200", "ftp://localhost", "/relative")) {
            assertThatThrownBy(() -> new CorsProperties(List.of(bad)))
                    .as(bad).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void unsetOrEmptyMeansNoCrossOriginCaller() {
        assertThat(new CorsProperties(null).allowedOrigins()).isEmpty();
        assertThat(new CorsProperties(List.of()).allowedOrigins()).isEmpty();
        assertThat(new CorsProperties(List.of("")).allowedOrigins()).isEmpty();
    }

    @Test
    void entriesAreTrimmedAndKept() {
        assertThat(new CorsProperties(List.of(" http://localhost:4200 ", "https://localhost")).allowedOrigins())
                .containsExactly("http://localhost:4200", "https://localhost");
    }
}
