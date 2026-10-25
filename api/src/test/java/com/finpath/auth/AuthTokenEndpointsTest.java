package com.finpath.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** POST /auth/token and POST /auth/refresh over the real filter chain. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class AuthTokenEndpointsTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OneTimeCodeStore codes;

    @Autowired
    private JwtDecoder decoder;

    @Autowired
    private ObjectMapper json;

    private final String verifier = OpaqueTokens.generate();

    private String issue(UUID user) {
        return codes.issue(user, Pkce.s256(verifier));
    }

    private ResultActions exchange(String code) throws Exception {
        return exchange(code, verifier);
    }

    private ResultActions exchange(String code, String codeVerifier) throws Exception {
        java.util.Map<String, String> body = new java.util.HashMap<>();
        body.put("code", code);
        if (codeVerifier != null) {
            body.put("codeVerifier", codeVerifier);
        }
        return mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    @Test
    void wrongVerifierIs401AndBurnsTheCode() throws Exception {
        String code = issue(UUID.randomUUID());
        exchange(code, OpaqueTokens.generate()).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        exchange(code).andExpect(status().isUnauthorized());
    }

    @Test
    void missingVerifierIs401AndBurnsTheCode() throws Exception {
        String code = issue(UUID.randomUUID());
        exchange(code, null).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
        exchange(code).andExpect(status().isUnauthorized());
    }

    private ResultActions refresh(String token) throws Exception {
        return mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("refreshToken", token))));
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    @Test
    void codeExchangesForAccessAndRefreshTokens() throws Exception {
        UUID user = UUID.randomUUID();
        ResultActions result = exchange(issue(user))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900))
                .andExpect(jsonPath("$.refreshToken").isString());
        Jwt jwt = decoder.decode(body(result).get("accessToken").asText());
        assertThat(jwt.getSubject()).isEqualTo(user.toString());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("finpath");
        assertThat(jwt.getIssuedAt()).isNotNull();
        assertThat(java.time.Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).toMinutes()).isEqualTo(15);
    }

    @Test
    void codeIsSingleUse() throws Exception {
        String code = issue(UUID.randomUUID());
        exchange(code).andExpect(status().isOk());
        exchange(code).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void unknownCodeIs401() throws Exception {
        exchange("not-a-code").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void refreshRotatesAndTheOldTokenIsRejected() throws Exception {
        UUID user = UUID.randomUUID();
        String first = body(exchange(issue(user))).get("refreshToken").asText();

        JsonNode rotated = body(refresh(first).andExpect(status().isOk()));
        String second = rotated.get("refreshToken").asText();
        assertThat(second).isNotEqualTo(first);
        assertThat(decoder.decode(rotated.get("accessToken").asText()).getSubject()).isEqualTo(user.toString());

        refresh(first).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void replayingARotatedTokenRevokesTheFamily() throws Exception {
        String first = body(exchange(issue(UUID.randomUUID()))).get("refreshToken").asText();
        String second = body(refresh(first)).get("refreshToken").asText();

        refresh(first).andExpect(status().isUnauthorized());
        refresh(second).andExpect(status().isUnauthorized());
    }

    private ResultActions logout(String token) throws Exception {
        return mvc.perform(post("/auth/logout").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("refreshToken", token))));
    }

    @Test
    void logoutRevokesTheWholeFamilyAndIsIdempotent() throws Exception {
        UUID user = UUID.randomUUID();
        String first = body(exchange(issue(user))).get("refreshToken").asText();
        String second = body(refresh(first)).get("refreshToken").asText();
        String otherDevice = body(exchange(issue(user))).get("refreshToken").asText();

        logout(second).andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(""));

        refresh(second).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_grant"));
        refresh(first).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_grant"));
        refresh(otherDevice).andExpect(status().isOk());

        // Same answer for an already-revoked, an unknown and a missing token.
        logout(second).andExpect(status().isNoContent());
        logout("not-a-token").andExpect(status().isNoContent());
        mvc.perform(post("/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    void logoutWithTheSupersededTokenStillKillsTheSuccessor() throws Exception {
        String first = body(exchange(issue(UUID.randomUUID()))).get("refreshToken").asText();
        String second = body(refresh(first)).get("refreshToken").asText();
        logout(first).andExpect(status().isNoContent());
        refresh(second).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void issuedAccessTokenOpensTheApi() throws Exception {
        String access = body(exchange(issue(UUID.randomUUID()))).get("accessToken").asText();
        // No API endpoint exists yet in T29; an authenticated request reaches MVC and gets a 404
        // instead of the 401 the same request gets without the token.
        mvc.perform(get("/nothing-here")).andExpect(status().isUnauthorized());
        mvc.perform(get("/nothing-here").header("Authorization", "Bearer " + access))
                .andExpect(status().isNotFound());
    }
}
