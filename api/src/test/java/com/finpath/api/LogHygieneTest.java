package com.finpath.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.finpath.auth.AuthProperties;
import com.finpath.auth.JwtIssuer;
import com.finpath.auth.OneTimeCodeStore;
import com.finpath.auth.RefreshTokenStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T32 acceptance: a log appender on the root logger (DEBUG, so nothing is hidden by level) sees
 * the failure paths of sign-in, the token endpoints, bearer checks and the 500 handler, and the
 * captured text contains no amount, no email address and no bearer / refresh / one-time-code value.
 * The detectors are themselves tested, so a green run is not vacuous.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
@Import(LogHygieneTest.Config.class)
class LogHygieneTest {

    private static final String EMAIL = "priya.secret@example.com";
    private static final String VERIFIER = "v".repeat(43);

    @RestController
    static class Exploding {
        @GetMapping("/test-log/boom")
        String boom() {
            throw new IllegalStateException("txn SWIGGY Rs 1,234.50 for " + EMAIL + " failed");
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        Exploding exploding() {
            return new Exploding();
        }
    }

    @Autowired MockMvc mvc;
    @Autowired JwtIssuer issuer;
    @Autowired RefreshTokenStore refreshTokens;
    @Autowired OneTimeCodeStore codes;
    @Autowired AuthProperties props;

    private LogCapture logs;
    private final List<String> secrets = new ArrayList<>();

    @BeforeEach
    void attach() {
        logs = LogCapture.start();
    }

    @AfterEach
    void detach() {
        logs.close();
    }

    private String captured() {
        return logs.text();
    }

    private void assertClean(String text) {
        LogCapture.assertClean(text, secrets);
    }

    // ---- the detectors catch what they claim to --------------------------------------------

    @Test
    void detectorsFlagPlantedLeaks() {
        assertThat(LogCapture.EMAIL_PATTERN.matcher("failed for a.b@example.co.in").find()).isTrue();
        for (String amount : List.of("paid ₹500", "Rs 1,234.50", "Rs.500", "INR 1200", "12.50", "12,34,567", "1,234")) {
            assertThat(LogCapture.AMOUNT_PATTERN.matcher(amount).find()).as(amount).isTrue();
        }
        assertThat(LogCapture.BEARER_PATTERN.matcher("Authorization: Bearer abcdefgh12345678").find()).isTrue();
        assertThat(LogCapture.BEARER_PATTERN.matcher("token eyJhbGciOi.eyJzdWIiOi.sig").find()).isTrue();
        assertThat(LogCapture.AMOUNT_PATTERN.matcher("Sign-in failed: user lookup/creation error (IllegalStateException)").find())
                .isFalse();
    }

    @Test
    void theAppenderActuallySeesLogOutput() throws Exception {
        mvc.perform(get("/test-log/boom").header("Authorization", "Bearer " + issuer.issue(UUID.randomUUID()).value()));
        assertThat(captured()).contains("Unhandled exception (IllegalStateException)");
    }

    // ---- failure paths ---------------------------------------------------------------------

    @Test
    void tokenEndpointFailuresLogNoCodeOrRefreshValue() throws Exception {
        UUID id = UUID.randomUUID();
        String code = codes.issue(id, "x".repeat(43));
        String wrongVerifierAttempt = "w".repeat(43);
        String refresh = refreshTokens.issue(id);
        String badRefresh = "not-a-real-refresh-token-" + "q".repeat(30);
        secrets.addAll(List.of(code, wrongVerifierAttempt, refresh, badRefresh, VERIFIER));

        mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"codeVerifier\":\"" + wrongVerifierAttempt + "\"}"));
        mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"codeVerifier\":\"" + VERIFIER + "\"}"));
        mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\",\"codeVerifier\":"));            // malformed JSON
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + badRefresh + "\"}"));
        String rotated = mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content("{\"refreshToken\":\"" + refresh + "\"}")).andReturn().getResponse().getContentAsString();
        secrets.add(rotated.replaceAll(".*\"refreshToken\":\"([^\"]+)\".*", "$1"));
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)           // replay of a spent token
                .content("{\"refreshToken\":\"" + refresh + "\"}"));

        assertClean(captured());
    }

    @Test
    void bearerFailuresLogNoTokenValue() throws Exception {
        String valid = issuer.issue(UUID.randomUUID()).value();
        String tampered = valid.substring(0, valid.length() - 4) + "AAAA";
        String garbage = "garbage-token-value-" + "z".repeat(30);
        secrets.addAll(List.of(valid, tampered, garbage));

        mvc.perform(get("/me/export").header("Authorization", "Bearer " + tampered));
        mvc.perform(get("/me/export").header("Authorization", "Bearer " + garbage));
        mvc.perform(get("/me/export").header("Authorization", "Bearer"));
        mvc.perform(get("/test-log/boom").header("Authorization", "Bearer " + valid));
        mvc.perform(get("/no-such-route").header("Authorization", "Bearer " + valid));

        assertClean(captured());
    }

    @Test
    void a500LogsTheExceptionClassOnly() throws Exception {
        mvc.perform(get("/test-log/boom").header("Authorization", "Bearer " + issuer.issue(UUID.randomUUID()).value()));
        String text = captured();
        assertThat(text).contains("IllegalStateException").doesNotContain("failed").doesNotContain("txn");
        assertClean(text);
    }
}
