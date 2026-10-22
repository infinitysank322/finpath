package com.finpath.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects a sign-in start whose parameters fail {@link PendingLogin#parse} with a 400 JSON body,
 * before anything is sent to Google. It matches requests with exactly the matcher Spring's
 * {@code DefaultOAuth2AuthorizationRequestResolver} uses, so an encoded path such as
 * {@code /oauth2/%61uthorization/google} cannot reach the resolver unchecked. Only installed in
 * the sign-in chain, never as a global servlet filter.
 */
final class ReturnUrlGuardFilter extends OncePerRequestFilter {

    static final String AUTHORIZATION_BASE = "/oauth2/authorization";
    static final RequestMatcher AUTHORIZATION_REQUEST =
            PathPatternRequestMatcher.withDefaults().matcher(AUTHORIZATION_BASE + "/{registrationId}");

    private final ReturnUrlAllowList allowList;

    ReturnUrlGuardFilter(ReturnUrlAllowList allowList) {
        this.allowList = allowList;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !AUTHORIZATION_REQUEST.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            PendingLogin.parse(request, allowList);
        } catch (PendingLogin.Rejected rejected) {
            JsonErrors.write(response, HttpServletResponse.SC_BAD_REQUEST, rejected.error());
            return;
        }
        chain.doFilter(request, response);
    }
}
