package com.finpath.auth;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Short-lived, single-use codes handed to the client on the post-login redirect and exchanged at
 * {@code POST /auth/token}. The JWT itself never travels in a URL. Each code is bound to the PKCE
 * S256 challenge the client sent when it started the sign-in, so an app that intercepts the
 * redirect (e.g. a hijacked custom-scheme deep link) cannot redeem it. Only SHA-256 hashes of codes
 * are held.
 *
 * <p>Known limitation (T29): in-memory, so codes do not survive a restart and do not work across
 * more than one instance. With a 60 s lifetime that only costs a retry; a shared store is a later
 * {@code [+TABLE]} task if the API is ever scaled out.
 */
@Component
public class OneTimeCodeStore {

    private record Entry(UUID userId, String codeChallenge, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, Entry> byHash = new ConcurrentHashMap<>();
    private final AuthProperties properties;
    private final Clock clock;

    @Autowired
    public OneTimeCodeStore(AuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    OneTimeCodeStore(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** @param codeChallenge the client's S256 challenge from the start of this sign-in */
    public String issue(UUID userId, String codeChallenge) {
        if (codeChallenge == null || codeChallenge.isBlank()) {
            throw new IllegalArgumentException("a one-time code must be bound to a PKCE challenge");
        }
        Instant now = clock.instant();
        byHash.values().removeIf(e -> !now.isBefore(e.expiresAt()));
        String code = OpaqueTokens.generate();
        byHash.put(OpaqueTokens.hash(code), new Entry(userId, codeChallenge, now.plus(properties.oneTimeCodeTtl())));
        return code;
    }

    /**
     * Atomically removes the code first, so it is burned by any attempt, right or wrong: a second
     * use fails even if concurrent, and a wrong verifier cannot be retried against the same code.
     */
    public Optional<UUID> consume(String code, String codeVerifier) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        Entry entry = byHash.remove(OpaqueTokens.hash(code));
        if (entry == null || !clock.instant().isBefore(entry.expiresAt())
                || !Pkce.matches(codeVerifier, entry.codeChallenge())) {
            return Optional.empty();
        }
        return Optional.of(entry.userId());
    }
}
