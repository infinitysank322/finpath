package com.finpath.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * THE seam for "who is calling": the owning user id ({@code users.id}) taken from the verified
 * access token's {@code sub}. Never take a user id from a request parameter or body. Controllers
 * use {@code @CurrentUserId UUID}; T30's connection initialiser calls {@link #id()} to set
 * {@code app.current_user_id}.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    /** The authenticated user's id, or empty when the request carries no valid access token. */
    public static Optional<UUID> id() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken jwt && jwt.isAuthenticated()) {
            // sub is validated as a UUID when the token is decoded (SecurityConfig).
            return Optional.of(UUID.fromString(jwt.getToken().getSubject()));
        }
        return Optional.empty();
    }

    /** The authenticated user's id; 401 if absent. */
    public static UUID require() {
        return id().orElseThrow(() -> new AuthenticationCredentialsNotFoundException("no authenticated user"));
    }
}
