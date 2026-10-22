package com.finpath.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Users whose account was deleted (DELETE /me). Access tokens are stateless, so a token issued
 * before deletion would otherwise stay valid until it expires; the JWT decoder consults this set
 * and rejects any token whose {@code sub} is in it. An entry only has to outlive the longest
 * possible access token (TTL plus the decoder's clock skew), so it is dropped after that.
 *
 * <p>In memory, like the token stores (single instance, T29 section 7). After a restart a
 * pre-deletion access token would verify for the rest of its life (at most the access TTL), but it
 * reaches no data: the users and consent rows are gone, so row-level security matches nothing and
 * the consent check fails. Refresh tokens and one-time codes do not survive a restart at all.
 */
@Component
public class DeletedUsers {

    private static final Duration SKEW_AND_MARGIN = Duration.ofMinutes(2);

    private final ConcurrentHashMap<UUID, Instant> until = new ConcurrentHashMap<>();
    private final AuthProperties properties;
    private final Clock clock;

    @Autowired
    public DeletedUsers(AuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    DeletedUsers(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void markDeleted(UUID userId) {
        Instant now = clock.instant();
        until.values().removeIf(t -> !now.isBefore(t));
        until.put(userId, now.plus(properties.accessTokenTtl()).plus(SKEW_AND_MARGIN));
    }

    public boolean isDeleted(UUID userId) {
        Instant t = until.get(userId);
        return t != null && clock.instant().isBefore(t);
    }
}
