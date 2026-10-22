package com.finpath.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finpath.user.UserAccounts;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.util.UriComponentsBuilder;

/** The OAuth success/failure handlers with a mocked Google user and a mocked users repository. */
@ExtendWith(OutputCaptureExtension.class)
class GoogleLoginHandlersTest {

    private static final String RETURN = "http://localhost:4200/auth/callback";
    private static final String APP_RETURN = "com.finpath.app://auth/callback";

    private final String verifier = OpaqueTokens.generate();
    private final String challenge = Pkce.s256(verifier);

    private UserAccounts users;
    private OneTimeCodeStore codes;
    private GoogleLoginHandlers handlers;
    private MockHttpServletRequest request;
    private MockHttpServletResponse response;
    private MockHttpSession session;

    @BeforeEach
    void setUp() {
        AuthProperties props = new AuthProperties("k".repeat(32), List.of(RETURN, APP_RETURN), null, null, null);
        users = mock(UserAccounts.class);
        codes = new OneTimeCodeStore(props);
        handlers = new GoogleLoginHandlers(users, codes, new ReturnUrlAllowList(props));
        request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        session = new MockHttpSession();
        request.setSession(session);
        response = new MockHttpServletResponse();
    }

    private void pending(String url, String state) {
        request.setAttribute(ReturnUrlExposingAuthorizationRequestRepository.REQUEST_ATTRIBUTE,
                new PendingLogin(url, challenge, state));
    }

    private static OAuth2AuthenticationToken google(String email, Object verified, String name) {
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", "1098765");
        if (email != null) {
            attrs.put("email", email);
        }
        if (verified != null) {
            attrs.put("email_verified", verified);
        }
        if (name != null) {
            attrs.put("name", name);
        }
        var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), attrs, "sub");
        return new OAuth2AuthenticationToken(user, user.getAuthorities(), "google");
    }

    private Map<String, String> redirectQuery() {
        return UriComponentsBuilder.fromUriString(response.getRedirectedUrl()).build().getQueryParams()
                .toSingleValueMap();
    }

    @Test
    void verifiedUserIsUpsertedAndRedirectedWithAPkceBoundSingleUseCode() throws Exception {
        UUID id = UUID.randomUUID();
        when(users.findOrCreateByVerifiedEmail("asha@example.com", "Asha K")).thenReturn(id);
        pending(RETURN, null);

        handlers.onAuthenticationSuccess(request, response, google("Asha@Example.COM", true, "Asha K"));

        verify(users).findOrCreateByVerifiedEmail("asha@example.com", "Asha K");
        assertThat(response.getRedirectedUrl()).startsWith(RETURN + "?code=").doesNotContain("state=");
        String code = redirectQuery().get("code");
        assertThat(code).matches("[A-Za-z0-9_-]{43}");
        assertThat(response.getRedirectedUrl()).doesNotContain("eyJ"); // no JWT in the URL
        assertThat(codes.consume(code, verifier)).contains(id);
        assertThat(codes.consume(code, verifier)).isEmpty();
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void codeIsBoundToTheChallengeOfThisLogin() throws Exception {
        when(users.findOrCreateByVerifiedEmail(anyString(), any())).thenReturn(UUID.randomUUID());
        pending(RETURN, null);
        handlers.onAuthenticationSuccess(request, response, google("a@example.com", true, "A"));
        assertThat(codes.consume(redirectQuery().get("code"), OpaqueTokens.generate())).isEmpty();
    }

    @Test
    void clientStateIsEchoedOnEveryReturnRedirect() throws Exception {
        when(users.findOrCreateByVerifiedEmail(anyString(), any())).thenReturn(UUID.randomUUID());
        pending(RETURN, "s-123");
        handlers.onAuthenticationSuccess(request, response, google("a@example.com", true, "A"));
        assertThat(redirectQuery()).containsEntry("state", "s-123").containsKey("code");

        response = new MockHttpServletResponse();
        handlers.onAuthenticationSuccess(request, response, google("a@example.com", false, "A"));
        assertThat(response.getRedirectedUrl()).isEqualTo(RETURN + "?error=email_not_verified&state=s-123");

        response = new MockHttpServletResponse();
        handlers.onAuthenticationFailure(request, response, new OAuth2AuthenticationException("access_denied"));
        assertThat(response.getRedirectedUrl()).isEqualTo(RETURN + "?error=login_failed&state=s-123");
    }

    @Test
    void androidDeepLinkReturnWorksToo() throws Exception {
        when(users.findOrCreateByVerifiedEmail(anyString(), any())).thenReturn(UUID.randomUUID());
        pending(APP_RETURN, null);
        handlers.onAuthenticationSuccess(request, response, google("a@example.com", "true", null));
        assertThat(response.getRedirectedUrl()).startsWith(APP_RETURN + "?code=");
    }

    @Test
    void unverifiedEmailIsNotLinkedToAnyAccount() throws Exception {
        pending(RETURN, null);
        handlers.onAuthenticationSuccess(request, response, google("a@example.com", false, "A"));
        verify(users, never()).findOrCreateByVerifiedEmail(anyString(), any());
        assertThat(response.getRedirectedUrl()).isEqualTo(RETURN + "?error=email_not_verified");
    }

    @Test
    void missingVerificationClaimOrEmailIsTreatedAsUnverified() throws Exception {
        pending(RETURN, null);
        handlers.onAuthenticationSuccess(request, response, google("a@example.com", null, "A"));
        assertThat(response.getRedirectedUrl()).isEqualTo(RETURN + "?error=email_not_verified");

        response = new MockHttpServletResponse();
        handlers.onAuthenticationSuccess(request, response, google(null, true, "A"));
        assertThat(response.getRedirectedUrl()).isEqualTo(RETURN + "?error=email_not_verified");
        verify(users, never()).findOrCreateByVerifiedEmail(anyString(), any());
    }

    @Test
    void databaseFailureRedirectsWithLoginFailedAndLogsNoPersonalData(CapturedOutput output) throws Exception {
        String email = "priya.secret@example.com";
        when(users.findOrCreateByVerifiedEmail(anyString(), any())).thenThrow(new DataAccessResourceFailureException(
                "INSERT INTO users (id, name, email) VALUES ('x', 'Priya Secretname', '" + email + "') failed"));
        pending(RETURN, "s1");
        handlers.onAuthenticationSuccess(request, response, google(email, true, "Priya Secretname"));

        assertThat(response.getRedirectedUrl()).isEqualTo(RETURN + "?error=login_failed&state=s1");
        assertThat(output.getAll()).contains("DataAccessResourceFailureException")
                .doesNotContain(email).doesNotContain("Secretname").doesNotContain("INSERT");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void transactionFailureIsAlsoALoginFailure(CapturedOutput output) throws Exception {
        when(users.findOrCreateByVerifiedEmail(anyString(), any()))
                .thenThrow(new CannotCreateTransactionException("db asleep for a@example.com"));
        pending(RETURN, null);
        handlers.onAuthenticationSuccess(request, response, google("a@example.com", true, "A"));
        assertThat(response.getRedirectedUrl()).isEqualTo(RETURN + "?error=login_failed");
        assertThat(output.getAll()).doesNotContain("a@example.com");
    }

    @Test
    void anyOtherRuntimeFailureIsALoginFailureWithAClassNameOnlyLog(CapturedOutput output) throws Exception {
        when(users.findOrCreateByVerifiedEmail(anyString(), any()))
                .thenThrow(new IllegalStateException("user row neither inserted nor found for meera@example.com"));
        pending(RETURN, "s2");
        handlers.onAuthenticationSuccess(request, response, google("meera@example.com", true, "Meera Q"));

        assertThat(response.getRedirectedUrl()).isEqualTo(RETURN + "?error=login_failed&state=s2");
        assertThat(output.getAll()).contains("IllegalStateException")
                .doesNotContain("meera@example.com").doesNotContain("Meera Q").doesNotContain("neither inserted");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void returnUrlIsRevalidatedAtTheEnd() throws Exception {
        pending("https://evil.example/auth/callback", null);
        handlers.onAuthenticationSuccess(request, response, google("a@example.com", true, "A"));
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getRedirectedUrl()).isNull();
        verify(users, never()).findOrCreateByVerifiedEmail(anyString(), any());
    }

    @Test
    void tamperedPendingStateOrChallengeIsRejected() throws Exception {
        request.setAttribute(ReturnUrlExposingAuthorizationRequestRepository.REQUEST_ATTRIBUTE,
                new PendingLogin(RETURN, challenge, "bad state&x=1"));
        handlers.onAuthenticationSuccess(request, response, google("a@example.com", true, "A"));
        assertThat(response.getStatus()).isEqualTo(400);

        response = new MockHttpServletResponse();
        request.setAttribute(ReturnUrlExposingAuthorizationRequestRepository.REQUEST_ATTRIBUTE,
                new PendingLogin(RETURN, null, null));
        handlers.onAuthenticationSuccess(request, response, google("a@example.com", true, "A"));
        assertThat(response.getStatus()).isEqualTo(400);
        verify(users, never()).findOrCreateByVerifiedEmail(anyString(), any());
    }

    @Test
    void missingPendingLoginIs400() throws Exception {
        handlers.onAuthenticationSuccess(request, response, google("a@example.com", true, "A"));
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("invalid_redirect");
    }

    @Test
    void nonOAuthPrincipalIsALoginFailure() throws Exception {
        pending(RETURN, null);
        handlers.onAuthenticationSuccess(request, response, new TestingAuthenticationToken("x", "y"));
        assertThat(response.getRedirectedUrl()).isEqualTo(RETURN + "?error=login_failed");
    }

    @Test
    void failureRedirectsToAllowedReturnUrlWithAnErrorOnly() throws Exception {
        pending(RETURN, null);
        handlers.onAuthenticationFailure(request, response, new OAuth2AuthenticationException("access_denied"));
        assertThat(response.getRedirectedUrl()).isEqualTo(RETURN + "?error=login_failed");
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void failureWithoutPendingLoginIs401Json() throws Exception {
        handlers.onAuthenticationFailure(request, response, new OAuth2AuthenticationException("invalid_state"));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("login_failed").doesNotContain("invalid_state");
    }
}
