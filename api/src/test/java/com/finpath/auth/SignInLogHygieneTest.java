package com.finpath.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finpath.api.LogCapture;
import com.finpath.user.UserAccounts;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;

/** T32 (T29 follow-up): the sign-in failure path, captured through a log appender, carries no email. */
class SignInLogHygieneTest {

    private static final String EMAIL = "priya.secret@example.com";

    private LogCapture logs;

    @BeforeEach
    void attach() {
        logs = LogCapture.start();
    }

    @AfterEach
    void detach() {
        logs.close();
    }

    @Test
    void signInFailurePathLogsNoEmailOrName() throws Exception {
        UserAccounts users = mock(UserAccounts.class);
        when(users.findOrCreateByVerifiedEmail(anyString(), any())).thenThrow(new DataAccessResourceFailureException(
                "INSERT INTO users (name, email) VALUES ('Priya Secretname', '" + EMAIL + "') Rs 1,234.50"));
        AuthProperties p = new AuthProperties("k".repeat(32), List.of("http://localhost:4200/auth/callback"),
                null, null, null);
        var handlers = new GoogleLoginHandlers(users, new OneTimeCodeStore(p),
                new ReturnUrlAllowList(p));

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        request.setSession(new MockHttpSession());
        request.setAttribute(ReturnUrlExposingAuthorizationRequestRepository.REQUEST_ATTRIBUTE,
                new PendingLogin("http://localhost:4200/auth/callback", "c".repeat(43), "s1"));
        Map<String, Object> attrs = new HashMap<>();
        attrs.put("sub", "1098765");
        attrs.put("email", EMAIL);
        attrs.put("email_verified", true);
        attrs.put("name", "Priya Secretname");
        var user = new DefaultOAuth2User(List.of(new SimpleGrantedAuthority("OAUTH2_USER")), attrs, "sub");
        handlers.onAuthenticationSuccess(request, new MockHttpServletResponse(),
                new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"));

        // Provider-side failure whose message carries the email.
        MockHttpServletRequest failed = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        failed.setSession(new MockHttpSession());
        handlers.onAuthenticationFailure(failed, new MockHttpServletResponse(),
                new OAuth2AuthenticationException("access_denied for " + EMAIL));

        String text = logs.text();
        assertThat(text).contains("Sign-in failed").contains("DataAccessResourceFailureException");
        assertThat(text).doesNotContain("Secretname").doesNotContain("INSERT");
        LogCapture.assertClean(text, List.of());
    }
}
