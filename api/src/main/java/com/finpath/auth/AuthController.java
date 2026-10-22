package com.finpath.auth;

import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Token endpoints (public; they authenticate by the code or refresh token in the body).
 * {@code POST /auth/token {code, codeVerifier}} exchanges the sign-in one-time code (PKCE S256
 * checked; the code is burned by any attempt); {@code POST /auth/refresh {refreshToken}}
 * rotates a refresh token. Any failure is the same 401 {@code invalid_grant}.
 */
@RestController
@RequestMapping("/auth")
public class AuthController {

    /** {@code codeVerifier}: the PKCE verifier whose S256 the client sent as {@code code_challenge}. */
    public record CodeRequest(String code, String codeVerifier) {
    }

    public record RefreshRequest(String refreshToken) {
    }

    public record TokenResponse(String accessToken, String tokenType, long expiresIn, String refreshToken) {
    }

    private final OneTimeCodeStore codes;
    private final RefreshTokenStore refreshTokens;
    private final JwtIssuer jwt;

    public AuthController(OneTimeCodeStore codes, RefreshTokenStore refreshTokens, JwtIssuer jwt) {
        this.codes = codes;
        this.refreshTokens = refreshTokens;
        this.jwt = jwt;
    }

    @PostMapping("/token")
    public ResponseEntity<?> token(@RequestBody CodeRequest request) {
        return codes.consume(request.code(), request.codeVerifier())
                .<ResponseEntity<?>>map(userId -> ok(userId, refreshTokens.issue(userId)))
                .orElseGet(AuthController::invalidGrant);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@RequestBody RefreshRequest request) {
        return refreshTokens.rotate(request.refreshToken())
                .<ResponseEntity<?>>map(r -> ok(r.userId(), r.refreshToken()))
                .orElseGet(AuthController::invalidGrant);
    }

    private ResponseEntity<TokenResponse> ok(UUID userId, String refreshToken) {
        JwtIssuer.AccessToken access = jwt.issue(userId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma", "no-cache")
                .body(new TokenResponse(access.value(), "Bearer", access.expiresInSeconds(), refreshToken));
    }

    private static ResponseEntity<Map<String, Object>> invalidGrant() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore())
                .body(Map.of("status", 401, "error", "invalid_grant"));
    }
}
