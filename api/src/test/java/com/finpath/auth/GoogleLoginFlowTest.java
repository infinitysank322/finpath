package com.finpath.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.HttpSession;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/** Start of the Google sign-in: parameter validation and how the pending login is carried. */
class GoogleLoginFlowTest {

    static final String START = "/oauth2/authorization/google";
    static final String ALLOWED = "http://localhost:4200/auth/callback";
    static final String CHALLENGE = Pkce.s256(OpaqueTokens.generate());

    static MockHttpServletRequestBuilder start() {
        return get(START).param("redirect_uri", ALLOWED).param("code_challenge", CHALLENGE)
                .param("code_challenge_method", "S256");
    }

    @Nested
    @SpringBootTest
    @AutoConfigureMockMvc
    @ActiveProfiles("ci")
    class ViaMockMvc {

        @Autowired
        private MockMvc mvc;

        @Test
        void unknownReturnUrlIsRejectedBeforeGoogle() throws Exception {
            for (String bad : new String[] {"https://evil.example/auth/callback", ALLOWED + "/", ALLOWED + "?x=1",
                    "//evil.example", "javascript:alert(1)"}) {
                mvc.perform(get(START).param("redirect_uri", bad).param("code_challenge", CHALLENGE)
                                .param("code_challenge_method", "S256"))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.error").value("invalid_redirect"));
            }
        }

        @Test
        void missingOrRepeatedReturnUrlIsRejected() throws Exception {
            mvc.perform(get(START).param("code_challenge", CHALLENGE).param("code_challenge_method", "S256"))
                    .andExpect(status().isBadRequest());
            mvc.perform(start().param("redirect_uri", "https://evil.example/cb"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void pkceChallengeIsRequiredAndOnlyS256IsAccepted() throws Exception {
            mvc.perform(get(START).param("redirect_uri", ALLOWED).param("code_challenge_method", "S256"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
            mvc.perform(get(START).param("redirect_uri", ALLOWED).param("code_challenge", CHALLENGE))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
            mvc.perform(get(START).param("redirect_uri", ALLOWED).param("code_challenge", CHALLENGE)
                            .param("code_challenge_method", "plain"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
            mvc.perform(get(START).param("redirect_uri", ALLOWED).param("code_challenge", "too-short")
                            .param("code_challenge_method", "S256"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"));
        }

        @Test
        void clientStateIsValidated() throws Exception {
            mvc.perform(start().param("state", "has space")).andExpect(status().isBadRequest());
            mvc.perform(start().param("state", "a".repeat(129))).andExpect(status().isBadRequest());
            mvc.perform(start().param("state", "a", "b")).andExpect(status().isBadRequest());
            mvc.perform(start().param("state", "Abc-123._~")).andExpect(status().is3xxRedirection());
        }

        @Test
        void unknownRegistrationIs404NotA500() throws Exception {
            mvc.perform(get("/oauth2/authorization/github").param("redirect_uri", ALLOWED)
                            .param("code_challenge", CHALLENGE).param("code_challenge_method", "S256"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error").value("not_found"));
        }

        @Test
        void allowedStartRedirectsToGoogleAndKeepsThePendingLoginServerSide() throws Exception {
            MvcResult result = mvc.perform(start().param("state", "client-state-1"))
                    .andExpect(status().is3xxRedirection())
                    .andReturn();
            URI location = URI.create(result.getResponse().getRedirectedUrl());
            assertThat(location.getHost()).isEqualTo("accounts.google.com");
            var query = UriComponentsBuilder.fromUri(location).build().getQueryParams();
            // Google gets the API's own callback and its own state; nothing the client sent.
            assertThat(query.getFirst("redirect_uri")).endsWith("/login/oauth2/code/google");
            assertThat(location.toString()).doesNotContain("4200").doesNotContain(CHALLENGE)
                    .doesNotContain("client-state-1");
            assertThat(query.getFirst("scope")).contains("openid").contains("email").contains("profile");
            assertThat(query.getFirst("state")).isNotBlank();

            HttpSession session = result.getRequest().getSession(false);
            assertThat(session).isNotNull();
            MockHttpServletRequest callback = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
            callback.setSession(session);
            callback.setParameter("state", URLDecoder.decode(query.getFirst("state"), StandardCharsets.UTF_8));
            assertThat(new ReturnUrlExposingAuthorizationRequestRepository()
                    .removeAuthorizationRequest(callback, new MockHttpServletResponse())).isNotNull();
            assertThat(ReturnUrlExposingAuthorizationRequestRepository.pendingLogin(callback))
                    .isEqualTo(new PendingLogin(ALLOWED, CHALLENGE, "client-state-1"));
        }

        @Test
        void callbackWithoutAMatchingLoginIs401Json() throws Exception {
            mvc.perform(get("/login/oauth2/code/google").param("code", "x").param("state", "forged"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("login_failed"));
        }

        @Test
        void repositoryDoesNotExposeAnythingWhenNoRequestIsPending() {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setParameter("state", "none");
            new ReturnUrlExposingAuthorizationRequestRepository(new HttpSessionOAuth2AuthorizationRequestRepository())
                    .removeAuthorizationRequest(request, new MockHttpServletResponse());
            assertThat(ReturnUrlExposingAuthorizationRequestRepository.pendingLogin(request)).isNull();
        }
    }

    /** Real Tomcat, raw percent-encoded path: the guard must see what the resolver sees. */
    @Nested
    @SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
    @ActiveProfiles("ci")
    class EncodedPath {

        @LocalServerPort
        private int port;

        private HttpResponse<String> send(String rawPathAndQuery) throws Exception {
            HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
            return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPathAndQuery)).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        private static String query(String redirect) {
            return "?redirect_uri=" + redirect + "&code_challenge=" + CHALLENGE + "&code_challenge_method=S256";
        }

        @Test
        void encodedAuthorizationPathWithABadReturnUrlIsRejectedNotA500() throws Exception {
            HttpResponse<String> response = send("/oauth2/%61uthorization/google" + query("https://evil.example/cb"));
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.headers().firstValue("Location")).isEmpty();
        }

        @Test
        void encodedAuthorizationPathIsValidatedLikeThePlainOne() throws Exception {
            assertThat(send("/oauth2/%61uthorization/google" + query("https://evil.example/cb")).statusCode())
                    .isEqualTo(send(START + query("https://evil.example/cb")).statusCode());
        }

        @Test
        void encodedUnknownRegistrationIsNotA500() throws Exception {
            assertThat(send("/oauth2/%61uthorization/github" + query(ALLOWED)).statusCode()).isEqualTo(404);
        }
    }
}
