package com.finpath.auth;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Auth settings, bound from {@code finpath.auth.*}. Every value that matters comes from the
 * environment (application.yml maps FINPATH_JWT_SECRET and FINPATH_AUTH_ALLOWED_REDIRECTS); the
 * constructor fails startup when the configuration is unsafe, so a missing secret is a boot
 * error rather than a silently weak token. Messages never include the secret itself.
 *
 * @param jwtSecret        HS256 signing key; at least {@value #MIN_SECRET_BYTES} bytes (UTF-8)
 * @param allowedRedirects exact-match allow-list of client return URLs after sign-in
 * @param accessTokenTtl   lifetime of an access JWT
 * @param refreshTokenTtl  lifetime of an opaque refresh token
 * @param oneTimeCodeTtl   lifetime of the sign-in code handed to the client; at most 60 s
 */
@ConfigurationProperties("finpath.auth")
public record AuthProperties(
        String jwtSecret,
        List<String> allowedRedirects,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration oneTimeCodeTtl) {

    public static final int MIN_SECRET_BYTES = 32;
    public static final Duration MAX_ONE_TIME_CODE_TTL = Duration.ofSeconds(60);

    public AuthProperties {
        if (jwtSecret == null || jwtSecret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("FINPATH_JWT_SECRET must be set to at least "
                    + MIN_SECRET_BYTES + " bytes (e.g. `openssl rand -base64 48`)");
        }
        allowedRedirects = allowedRedirects == null ? List.of()
                : allowedRedirects.stream().map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (allowedRedirects.isEmpty()) {
            throw new IllegalStateException("FINPATH_AUTH_ALLOWED_REDIRECTS must list at least one return URL");
        }
        allowedRedirects.forEach(AuthProperties::requireWellFormedRedirect);
        accessTokenTtl = positive(accessTokenTtl, Duration.ofMinutes(15), "access-token-ttl");
        refreshTokenTtl = positive(refreshTokenTtl, Duration.ofDays(30), "refresh-token-ttl");
        oneTimeCodeTtl = positive(oneTimeCodeTtl, MAX_ONE_TIME_CODE_TTL, "one-time-code-ttl");
        if (oneTimeCodeTtl.compareTo(MAX_ONE_TIME_CODE_TTL) > 0) {
            throw new IllegalStateException("finpath.auth.one-time-code-ttl must be at most 60s");
        }
    }

    private static Duration positive(Duration value, Duration fallback, String name) {
        Duration d = value == null ? fallback : value;
        if (d.isNegative() || d.isZero()) {
            throw new IllegalStateException("finpath.auth." + name + " must be positive");
        }
        return d;
    }

    /** Absolute URI with a scheme, no fragment and no wildcard: the list is matched exactly. */
    private static void requireWellFormedRedirect(String url) {
        try {
            URI uri = new URI(url);
            if (!uri.isAbsolute() || uri.getRawFragment() != null || url.contains("*")) {
                throw new IllegalStateException("Allowed redirect is not an absolute URI without fragment: " + url);
            }
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Allowed redirect is not a valid URI: " + url, e);
        }
    }

    /** Never print the signing key. */
    @Override
    public String toString() {
        return "AuthProperties[jwtSecret=<redacted>, allowedRedirects=" + allowedRedirects
                + ", accessTokenTtl=" + accessTokenTtl + ", refreshTokenTtl=" + refreshTokenTtl
                + ", oneTimeCodeTtl=" + oneTimeCodeTtl + "]";
    }
}
