package com.finpath.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandlerImpl;

/**
 * 401 for a missing, malformed, tampered or expired bearer token: a fixed RFC 6750
 * {@code WWW-Authenticate} header and a small JSON body. Spring's default entry point would copy
 * the decoder's exception text into {@code error_description}; this one never does. Also hosts
 * the matching 403 handler.
 */
final class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    static final String MISSING_TOKEN_CHALLENGE = "Bearer";
    static final String INVALID_TOKEN_CHALLENGE = "Bearer error=\"invalid_token\"";

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        boolean invalidToken = ex instanceof OAuth2AuthenticationException;
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, invalidToken ? INVALID_TOKEN_CHALLENGE : MISSING_TOKEN_CHALLENGE);
        JsonErrors.write(response, HttpServletResponse.SC_UNAUTHORIZED, invalidToken ? "invalid_token" : "unauthorized");
    }

    static AccessDeniedHandler accessDeniedHandler() {
        AccessDeniedHandler fallback = new AccessDeniedHandlerImpl();
        return (request, response, ex) -> {
            if (response.isCommitted()) {
                fallback.handle(request, response, ex);
                return;
            }
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer error=\"insufficient_scope\"");
            JsonErrors.write(response, HttpServletResponse.SC_FORBIDDEN, "forbidden");
        };
    }
}
