package com.finpath.auth;

import com.finpath.user.UserAccounts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * End of the Google sign-in: resolve the verified identity to a FinPath user, then send the
 * browser back to the allow-listed return URL with a one-time code bound to the client's PKCE
 * challenge (never a token), plus the client's {@code state} if it sent one. Both outcomes end the
 * short login session, so nothing about the login outlives this request server-side.
 */
@Component
public class GoogleLoginHandlers implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(GoogleLoginHandlers.class);

    private final UserAccounts users;
    private final OneTimeCodeStore codes;
    private final ReturnUrlAllowList allowList;

    public GoogleLoginHandlers(UserAccounts users, OneTimeCodeStore codes, ReturnUrlAllowList allowList) {
        this.users = users;
        this.codes = codes;
        this.allowList = allowList;
    }

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
            Authentication authentication) throws IOException {
        PendingLogin login = ReturnUrlExposingAuthorizationRequestRepository.pendingLogin(request);
        endLoginSession(request);
        if (login == null || !login.stillValid(allowList)) {
            JsonErrors.write(response, HttpServletResponse.SC_BAD_REQUEST, "invalid_redirect");
            return;
        }
        if (!(authentication.getPrincipal() instanceof OAuth2User google)) {
            response.sendRedirect(login.redirect("error", "login_failed"));
            return;
        }
        String email = google.getAttribute("email");
        if (email == null || email.isBlank() || !isTrue(google.getAttribute("email_verified"))) {
            response.sendRedirect(login.redirect("error", "email_not_verified"));
            return;
        }
        UUID userId;
        try {
            userId = users.findOrCreateByVerifiedEmail(email.strip().toLowerCase(Locale.ROOT),
                    google.getAttribute("name"));
        } catch (RuntimeException e) {
            // Database errors and anything else: messages can carry SQL parameters (the email, the
            // name), so log the type only, and send the client a fixed error rather than a 500.
            log.warn("Sign-in failed: user lookup/creation error ({})", e.getClass().getSimpleName());
            response.sendRedirect(login.redirect("error", "login_failed"));
            return;
        }
        response.sendRedirect(login.redirect("code", codes.issue(userId, login.codeChallenge())));
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        PendingLogin login = ReturnUrlExposingAuthorizationRequestRepository.pendingLogin(request);
        endLoginSession(request);
        if (login != null && login.stillValid(allowList)) {
            response.sendRedirect(login.redirect("error", "login_failed"));
        } else {
            JsonErrors.write(response, HttpServletResponse.SC_UNAUTHORIZED, "login_failed");
        }
    }

    private static boolean isTrue(Object claim) {
        return Boolean.TRUE.equals(claim) || "true".equalsIgnoreCase(String.valueOf(claim));
    }

    private static void endLoginSession(HttpServletRequest request) {
        SecurityContextHolder.clearContext();
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }
}
