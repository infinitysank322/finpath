package com.finpath.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Builds Google's authorization request as usual and stores the client's {@link PendingLogin}
 * (return URL, PKCE challenge, client state) as attributes of that request. The attributes are
 * never sent to Google: they are saved server-side with the request (bound to Google's own
 * {@code state}) and read back when the callback removes it.
 */
final class ReturnUrlAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

    private static final Logger log = LoggerFactory.getLogger(ReturnUrlAuthorizationRequestResolver.class);

    static final String RETURN_URL = "finpath.return_url";
    static final String CODE_CHALLENGE = "finpath.code_challenge";
    static final String CLIENT_STATE = "finpath.client_state";

    /** The path names a registration id that is not configured (Spring's own exception type is package-private). */
    static final class UnknownRegistration extends RuntimeException {
        UnknownRegistration() {
            super("unknown client registration", null, false, false);
        }
    }

    private final DefaultOAuth2AuthorizationRequestResolver delegate;
    private final ClientRegistrationRepository registrations;
    private final ReturnUrlAllowList allowList;

    ReturnUrlAuthorizationRequestResolver(ClientRegistrationRepository registrations, ReturnUrlAllowList allowList) {
        this.delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations,
                ReturnUrlGuardFilter.AUTHORIZATION_BASE);
        this.registrations = registrations;
        this.allowList = allowList;
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        RequestMatcher.MatchResult match = ReturnUrlGuardFilter.AUTHORIZATION_REQUEST.matcher(request);
        if (match.isMatch()) {
            requireKnown(match.getVariables().get("registrationId"));
        }
        return attach(request, delegate.resolve(request));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        requireKnown(clientRegistrationId);
        return attach(request, delegate.resolve(request, clientRegistrationId));
    }

    private void requireKnown(String registrationId) {
        if (registrationId == null || registrations.findByRegistrationId(registrationId) == null) {
            throw new UnknownRegistration();
        }
    }

    private OAuth2AuthorizationRequest attach(HttpServletRequest request, OAuth2AuthorizationRequest resolved) {
        if (resolved == null) {
            return null;
        }
        // ReturnUrlGuardFilter already answered 400; this is defence in depth (throws Rejected).
        PendingLogin login = PendingLogin.parse(request, allowList);
        return OAuth2AuthorizationRequest.from(resolved).attributes(a -> {
            a.put(RETURN_URL, login.returnUrl());
            a.put(CODE_CHALLENGE, login.codeChallenge());
            if (login.clientState() != null) {
                a.put(CLIENT_STATE, login.clientState());
            }
        }).build();
    }

    /**
     * For the redirect filter. A rejected parameter is 400 with its code; an unknown registration
     * id is 404; anything else is a genuine server fault, 500 with a fixed body (no message).
     */
    static AuthenticationFailureHandler failureHandler() {
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
                -> writeFailure(response, ex);
    }

    private static void writeFailure(HttpServletResponse response, Throwable ex) throws IOException {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof PendingLogin.Rejected rejected) {
                JsonErrors.write(response, HttpServletResponse.SC_BAD_REQUEST, rejected.error());
                return;
            }
            if (t instanceof UnknownRegistration) {
                JsonErrors.write(response, HttpServletResponse.SC_NOT_FOUND, "not_found");
                return;
            }
        }
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        log.error("Sign-in start failed ({})", root.getClass().getSimpleName());
        JsonErrors.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "server_error");
    }
}
