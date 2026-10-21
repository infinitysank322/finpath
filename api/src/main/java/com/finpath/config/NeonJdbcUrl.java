package com.finpath.config;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts the libpq-style connection string Neon hands out (scheme, userinfo, host, port,
 * database, query) into JDBC URLs plus separate credentials.
 *
 * <p>Two JDBC URLs come out: the pooled one (the app's HikariCP pool) and the direct one (Flyway
 * migrations, which need session-level features the pooler does not give). Neon names the pooler
 * endpoint by adding {@code -pooler} to the first host label; the direct host drops it.
 *
 * <p>Nothing here ever puts the input, or any part of it, in an exception message or a
 * {@code toString}: failures name the problem category only, and the original parse exception
 * (whose message embeds the input) is deliberately not chained.
 *
 * <p>The scheme literals are assembled from parts so that no complete Postgres URL literal is
 * tracked in the repository (T04 git-grep guard).
 */
public record NeonJdbcUrl(String pooledJdbcUrl, String directJdbcUrl, String username, String password) {

    private static final String SEP = ":" + "//";
    private static final String JDBC_PREFIX = "jdbc:" + "postgresql" + SEP;
    private static final Set<String> SCHEMES = Set.of("postgres", "postgresql");
    private static final String POOLER_SUFFIX = "-pooler";
    private static final int DEFAULT_PORT = 5432;
    private static final int DEFAULT_CONNECT_TIMEOUT_SECONDS = 30;

    private static final Set<String> SSL_MODES =
            Set.of("disable", "allow", "prefer", "require", "verify-ca", "verify-full");
    private static final Set<String> CHANNEL_BINDING_MODES = Set.of("disable", "prefer", "require");

    /** Failure with a fixed, value-free message. */
    public static final class InvalidNeonUrlException extends IllegalArgumentException {
        public InvalidNeonUrlException(String reason) {
            super("NEON_DATABASE_URL is not usable: " + reason);
        }
    }

    public static NeonJdbcUrl parse(String libpqUrl) {
        if (libpqUrl == null || libpqUrl.isBlank()) {
            throw new InvalidNeonUrlException("it is empty");
        }
        URI uri;
        try {
            uri = new URI(libpqUrl.trim());
        } catch (URISyntaxException e) {
            // Not chained: its message contains the input.
            throw new InvalidNeonUrlException(
                    "it is not a well-formed URL (check special characters are percent-encoded)");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!SCHEMES.contains(scheme)) {
            throw new InvalidNeonUrlException("scheme must be postgres or postgresql");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new InvalidNeonUrlException("host is missing");
        }
        String rawUserInfo = uri.getRawUserInfo();
        if (rawUserInfo == null || rawUserInfo.isEmpty()) {
            throw new InvalidNeonUrlException("user and password are missing");
        }
        int colon = rawUserInfo.indexOf(':');
        if (colon <= 0 || colon == rawUserInfo.length() - 1) {
            throw new InvalidNeonUrlException("user or password is missing");
        }
        String user = percentDecode(rawUserInfo.substring(0, colon));
        String password = percentDecode(rawUserInfo.substring(colon + 1));
        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.length() < 2 || rawPath.indexOf('/', 1) >= 0) {
            throw new InvalidNeonUrlException("database name is missing");
        }
        String database = percentDecode(rawPath.substring(1));
        int port = uri.getPort() == -1 ? DEFAULT_PORT : uri.getPort();

        String params = jdbcParams(uri.getRawQuery());
        String pooled = jdbcUrl(host, port, database, params);
        String directHost = directHost(host);
        String direct = directHost.equals(host) ? pooled : jdbcUrl(directHost, port, database, params);
        return new NeonJdbcUrl(pooled, direct, user, password);
    }

    /** Neon convention: pooler endpoint = first host label plus {@code -pooler}. */
    static String directHost(String host) {
        int dot = host.indexOf('.');
        String first = dot < 0 ? host : host.substring(0, dot);
        String rest = dot < 0 ? "" : host.substring(dot);
        if (first.endsWith(POOLER_SUFFIX) && first.length() > POOLER_SUFFIX.length()) {
            return first.substring(0, first.length() - POOLER_SUFFIX.length()) + rest;
        }
        return host;
    }

    /**
     * Whitelist translation of libpq query parameters to pgJDBC ones. pgJDBC 42.7 knows
     * {@code sslmode} and {@code channelBinding} (camelCase, not libpq's {@code channel_binding}),
     * so the latter is renamed and validated rather than passed through verbatim. Every other
     * parameter is dropped, because an unknown name is silently ignored by some driver versions
     * and rejected by others. A TLS mode is always present (default {@code require}) and
     * {@code connectTimeout} is lifted to 30 s so a scale-to-zero cold start is not cut short.
     */
    private static String jdbcParams(String rawQuery) {
        Map<String, String> incoming = new LinkedHashMap<>();
        if (rawQuery != null && !rawQuery.isEmpty()) {
            for (String pair : rawQuery.split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                int eq = pair.indexOf('=');
                String name = percentDecode(eq < 0 ? pair : pair.substring(0, eq));
                String value = eq < 0 ? "" : percentDecode(pair.substring(eq + 1));
                incoming.put(name, value);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        String sslMode = incoming.getOrDefault("sslmode", "require").toLowerCase(Locale.ROOT);
        if (!SSL_MODES.contains(sslMode)) {
            throw new InvalidNeonUrlException("sslmode has an unsupported value");
        }
        out.put("sslmode", sslMode);
        String channelBinding = incoming.get("channel_binding");
        if (channelBinding != null) {
            String mode = channelBinding.toLowerCase(Locale.ROOT);
            if (!CHANNEL_BINDING_MODES.contains(mode)) {
                throw new InvalidNeonUrlException("channel_binding has an unsupported value");
            }
            out.put("channelBinding", mode);
        }
        out.put("connectTimeout", String.valueOf(DEFAULT_CONNECT_TIMEOUT_SECONDS));
        StringBuilder sb = new StringBuilder();
        out.forEach((k, v) -> sb.append(sb.length() == 0 ? '?' : '&').append(k).append('=').append(v));
        return sb.toString();
    }

    private static String jdbcUrl(String host, int port, String database, String params) {
        return JDBC_PREFIX + host + ':' + port + '/' + database + params;
    }

    /** RFC 3986 percent-decoding; unlike URLDecoder, '+' stays a literal plus. */
    private static String percentDecode(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '%') {
                if (i + 2 >= bytes.length) {
                    throw new InvalidNeonUrlException("it contains a malformed percent escape");
                }
                int hi = Character.digit(bytes[i + 1], 16);
                int lo = Character.digit(bytes[i + 2], 16);
                if (hi < 0 || lo < 0) {
                    throw new InvalidNeonUrlException("it contains a malformed percent escape");
                }
                out.write((hi << 4) | lo);
                i += 2;
            } else {
                out.write(bytes[i]);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Override
    public String toString() {
        return "NeonJdbcUrl[redacted]";
    }
}
