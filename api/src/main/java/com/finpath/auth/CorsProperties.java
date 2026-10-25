package com.finpath.auth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cross-origin allow-list for the bearer-JWT API chain, bound from {@code finpath.cors.*}
 * (application.yml maps FINPATH_CORS_ALLOWED_ORIGINS). Entries are exact origins
 * ({@code scheme://host[:port]}), matched exactly: no wildcards, no patterns, no paths. An unset
 * or empty variable means no cross-origin caller is allowed; a blank entry inside a list, {@code *},
 * {@code null} or anything that is not a bare origin fails startup.
 *
 * @param allowedOrigins exact-match list of browser origins allowed to call the API
 */
@ConfigurationProperties("finpath.cors")
public record CorsProperties(List<String> allowedOrigins) {

    static final String ENV = "FINPATH_CORS_ALLOWED_ORIGINS";

    public CorsProperties {
        List<String> raw = allowedOrigins == null ? List.of() : allowedOrigins;
        // An empty environment variable binds as nothing or as a single blank entry: that is "none allowed".
        if (raw.size() == 1 && (raw.get(0) == null || raw.get(0).isBlank())) {
            raw = List.of();
        }
        allowedOrigins = raw.stream().map(CorsProperties::requireOrigin).toList();
    }

    private static String requireOrigin(String entry) {
        String origin = entry == null ? "" : entry.trim();
        if (origin.isEmpty()) {
            throw new IllegalStateException(ENV + " must not contain blank entries");
        }
        if (origin.contains("*") || "null".equalsIgnoreCase(origin)) {
            throw new IllegalStateException(ENV + " must list exact origins; wildcards and 'null' are not allowed");
        }
        try {
            URI uri = new URI(origin);
            boolean bare = uri.isAbsolute() && uri.getHost() != null && uri.getRawUserInfo() == null
                    && uri.getRawPath() != null && uri.getRawPath().isEmpty()
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
            if (!bare) {
                throw new IllegalStateException(ENV + " entries must be bare origins like https://app.example (no path)");
            }
        } catch (URISyntaxException e) {
            throw new IllegalStateException(ENV + " contains an entry that is not a valid origin", e);
        }
        return origin;
    }
}
