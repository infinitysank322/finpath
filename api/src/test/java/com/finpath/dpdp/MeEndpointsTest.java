package com.finpath.dpdp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpath.auth.JwtIssuer;
import com.finpath.auth.OneTimeCodeStore;
import com.finpath.auth.RefreshTokenStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP contract of /me/* and the T29 follow-ups: after DELETE /me the user's old access token,
 * refresh tokens and live one-time codes all fail. Storage is mocked; the security chain, JWT
 * decoder and token stores are the real ones.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("ci")
class MeEndpointsTest {

    private static final String VERIFIER = "a".repeat(43);

    @Autowired MockMvc mvc;
    @Autowired JwtIssuer issuer;
    @Autowired RefreshTokenStore refreshTokens;
    @Autowired OneTimeCodeStore codes;
    @Autowired ObjectMapper mapper;
    @MockitoBean DataSubjectService data;
    @MockitoBean ConsentService consents;

    private static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private String bearer(UUID user) {
        return "Bearer " + issuer.issue(user).value();
    }

    private int codeOf(String path, String bearer) throws Exception {
        return mvc.perform(get(path).header("Authorization", bearer)).andReturn().getResponse().getStatus();
    }

    // ---- authentication ----------------------------------------------------------------------

    @Test
    void everyMeEndpointNeedsAToken() throws Exception {
        mvc.perform(get("/me/export")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/me/consent")).andExpect(status().isUnauthorized());
        mvc.perform(post("/me/consent").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/me/consent")).andExpect(status().isUnauthorized());
    }

    // ---- export ------------------------------------------------------------------------------

    @Test
    void exportReturnsTheServiceJsonAsAnAttachmentForThePrincipalOnly() throws Exception {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var body = mapper.readTree("{\"format\":1,\"tables\":{\"users\":[],\"consent\":[]}}");
        when(data.export(me)).thenReturn(body);

        mvc.perform(get("/me/export").param("userId", other.toString()).header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.tables.users").isArray())
                .andExpect(jsonPath("$.tables.consent").isArray());
        verify(data).export(me);
        verify(data, never()).export(other);
    }

    // ---- consent -----------------------------------------------------------------------------

    @Test
    void getConsentShowsTheStatementAndWhetherConsentIsGiven() throws Exception {
        UUID me = UUID.randomUUID();
        when(consents.current(me)).thenReturn(Optional.empty());
        mvc.perform(get("/me/consent").header("Authorization", bearer(me)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.statement.purpose").value(ConsentStatement.CURRENT.purpose()))
                .andExpect(jsonPath("$.statement.version").value(ConsentStatement.CURRENT.version()))
                .andExpect(jsonPath("$.statement.text").isNotEmpty())
                .andExpect(jsonPath("$.given").value(false));
    }

    @Test
    void postConsentRecordsItForThePrincipalIgnoringAnyIdInTheBody() throws Exception {
        UUID me = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var granted = new ConsentService.Consent(ConsentStatement.CURRENT.purpose(),
                ConsentStatement.CURRENT.version(), Instant.parse("2026-10-22T00:00:00Z"), null);
        when(consents.grant(me, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version()))
                .thenReturn(granted);
        mvc.perform(post("/me/consent").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"" + ConsentStatement.CURRENT.purpose() + "\",\"statementVersion\":\""
                                + ConsentStatement.CURRENT.version() + "\",\"userId\":\"" + other + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.statementVersion").value(ConsentStatement.CURRENT.version()));
        verify(consents, never()).grant(org.mockito.ArgumentMatchers.eq(other), any(), any());
    }

    @Test
    void staleStatementVersionIsA400WithAFixedCode() throws Exception {
        UUID me = UUID.randomUUID();
        when(consents.grant(any(), any(), any())).thenThrow(new ConsentStatementMismatchException());
        mvc.perform(post("/me/consent").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"purpose\":\"X\",\"statementVersion\":\"old\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_request"))
                .andExpect(jsonPath("$.message").doesNotExist());
    }

    @Test
    void withdrawingConsentIs204() throws Exception {
        UUID me = UUID.randomUUID();
        mvc.perform(delete("/me/consent").header("Authorization", bearer(me))).andExpect(status().isNoContent());
        verify(consents).withdraw(me);
    }

    @Test
    void consentRequiredMapsTo403WithAFixedCode() throws Exception {
        UUID me = UUID.randomUUID();
        when(data.export(me)).thenThrow(new ConsentRequiredException());
        mvc.perform(get("/me/export").header("Authorization", bearer(me)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("consent_required"));
    }

    // ---- deletion and token revocation -------------------------------------------------------

    @Test
    void afterDeleteTheOldAccessTokenRefreshTokensAndLiveOneTimeCodeAllFail() throws Exception {
        UUID me = UUID.randomUUID();
        UUID bystander = UUID.randomUUID();
        String access = bearer(me);
        String refresh = refreshTokens.issue(me);
        String refreshSecondFamily = refreshTokens.issue(me);
        String code = codes.issue(me, challenge(VERIFIER));
        String bystanderAccess = bearer(bystander);
        String bystanderRefresh = refreshTokens.issue(bystander);
        String bystanderCode = codes.issue(bystander, challenge(VERIFIER));
        assertThat(codeOf("/me/consent", access)).isEqualTo(200);

        mvc.perform(delete("/me").header("Authorization", access)).andExpect(status().isNoContent());
        verify(data).delete(me);

        // the old access token, still unexpired, is now refused
        mvc.perform(get("/me/consent").header("Authorization", access)).andExpect(status().isUnauthorized());
        // a token minted afterwards for the same sub is refused too
        mvc.perform(get("/me/consent").header("Authorization", bearer(me))).andExpect(status().isUnauthorized());
        // both refresh tokens
        for (String t : new String[] {refresh, refreshSecondFamily}) {
            mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"refreshToken\":\"" + t + "\"}"))
                    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_grant"));
        }
        // the one-time code that was live
        mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"codeVerifier\":\"" + VERIFIER + "\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").value("invalid_grant"));

        // another user is untouched
        assertThat(codeOf("/me/consent", bystanderAccess)).isEqualTo(200);
        assertThat(refreshTokens.rotate(bystanderRefresh)).isPresent();
        assertThat(codes.consume(bystanderCode, VERIFIER)).contains(bystander);
    }

    @Test
    void aOneTimeCodeIssuedAfterDeletionStillCannotBeRedeemedForTokens() throws Exception {
        UUID me = UUID.randomUUID();
        mvc.perform(delete("/me").header("Authorization", bearer(me))).andExpect(status().isNoContent());
        String lateCode = codes.issue(me, challenge(VERIFIER));
        mvc.perform(post("/auth/token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + lateCode + "\",\"codeVerifier\":\"" + VERIFIER + "\"}"))
                .andExpect(status().isUnauthorized());
        String lateRefresh = refreshTokens.issue(me);
        mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + lateRefresh + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aFailedDeletionRevokesNothing() throws Exception {
        UUID me = UUID.randomUUID();
        String access = bearer(me);
        String refresh = refreshTokens.issue(me);
        doThrow(new IllegalStateException("db down")).when(data).delete(me);
        // T32: the failure now surfaces as the generic 500 body instead of propagating out of MockMvc.
        mvc.perform(delete("/me").header("Authorization", access))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("server_error"));
        assertThat(codeOf("/me/consent", access)).isEqualTo(200);
        assertThat(refreshTokens.rotate(refresh)).isPresent();
    }
}
