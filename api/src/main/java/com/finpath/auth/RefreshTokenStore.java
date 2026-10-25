package com.finpath.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Opaque refresh tokens, rotated on every use. Only SHA-256 hashes are held. Each sign-in starts a
 * token family; presenting a token that was already rotated (a replay, so possibly stolen) revokes
 * the whole family, which forces a fresh Google sign-in.
 *
 * <p>Concurrency:
 * <ul>
 *   <li>A token's live-to-spent transition is one atomic {@code compute} on a single map, so of two
 *       concurrent presentations exactly one rotates and the other is seen as a replay.</li>
 *   <li>A revoked family is remembered; a rotation re-checks it after storing its new token.</li>
 *   <li>{@link #revokeAll} bumps a per-user revocation epoch <em>before</em> removing tokens. Every
 *       token carries the epoch it was issued under; a rotation refuses a token from an older
 *       epoch and re-checks the epoch after storing its new token. So no token issued by a
 *       rotation racing with {@code revokeAll} survives it, whichever side runs first.</li>
 * </ul>
 *
 * <p>Known limitation (T29): in-memory, so every refresh token is lost on restart (users sign in
 * again) and it does not work across instances. Persisting hashes is a later {@code [+TABLE]} task
 * (it then needs RLS, export and deletion like any other table). The epoch map keeps one small
 * entry per user ever revoked until restart.
 */
@Component
public class RefreshTokenStore {

    /** A new access-token subject and the refresh token that replaces the presented one. */
    public record Rotation(UUID userId, String refreshToken) {
    }

    private record Entry(UUID userId, UUID family, long epoch, Instant expiresAt, boolean spent) {
        Entry markSpent() {
            return new Entry(userId, family, epoch, expiresAt, true);
        }
    }

    private static final Duration PURGE_INTERVAL = Duration.ofMinutes(1);

    /** Live and spent tokens by hash; spent ones are kept until expiry to detect replay. */
    private final ConcurrentHashMap<String, Entry> tokens = new ConcurrentHashMap<>();
    /** Revoked family id to the instant after which no token of it can still be live. */
    private final ConcurrentHashMap<UUID, Instant> revokedFamilies = new ConcurrentHashMap<>();
    /** Per-user revocation epoch; absent means 0. Bumped by {@link #revokeAll}. */
    private final ConcurrentHashMap<UUID, Long> userEpochs = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastPurge = new AtomicReference<>(Instant.MIN);
    private final AuthProperties properties;
    private final Clock clock;

    /** Test seam: runs inside {@link #rotate} after the presented token is marked spent, before the new one is stored. */
    volatile Runnable afterMarkSpent = () -> { };
    /** Test seam: runs inside {@link #rotate} after the new token is stored, before the re-check. */
    volatile Runnable afterNewTokenStored = () -> { };

    @Autowired
    public RefreshTokenStore(AuthProperties properties) {
        this(properties, Clock.systemUTC());
    }

    RefreshTokenStore(AuthProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** Starts a new family for a fresh sign-in. */
    public String issue(UUID userId) {
        purgeExpiredIfDue();
        // If revokeAll races with this, the token either gets removed by it or carries the old
        // epoch, which rotate() refuses.
        return put(userId, UUID.randomUUID(), epoch(userId));
    }

    /** Exchanges a live token for a new one; the presented token is dead afterwards either way. */
    public Optional<Rotation> rotate(String token) {
        purgeExpiredIfDue();
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Entry[] before = new Entry[1];
        tokens.computeIfPresent(OpaqueTokens.hash(token), (hash, entry) -> {
            before[0] = entry;
            return entry.spent() ? entry : entry.markSpent();
        });
        Entry entry = before[0];
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.spent()) {
            revokeFamily(entry.family());
            return Optional.empty();
        }
        afterMarkSpent.run();
        if (!clock.instant().isBefore(entry.expiresAt()) || !stillAllowed(entry)) {
            return Optional.empty();
        }
        String next = put(entry.userId(), entry.family(), entry.epoch());
        afterNewTokenStored.run();
        if (!stillAllowed(entry)) {
            // A replay or revokeAll raced with this rotation: the new token must not survive.
            tokens.remove(OpaqueTokens.hash(next));
            return Optional.empty();
        }
        return Optional.of(new Rotation(entry.userId(), next));
    }

    /** Drops every refresh token of a user (sign-out everywhere, account deletion). */
    public void revokeAll(UUID userId) {
        userEpochs.merge(userId, 1L, Long::sum);
        // Spent hashes too: after account deletion nothing tied to the user should remain.
        tokens.values().removeIf(e -> e.userId().equals(userId));
    }

    /**
     * Sign-out: revokes the whole family of the presented token, whether it is live, already rotated
     * or expired. Unknown, blank or null tokens do nothing and say nothing (no oracle); other
     * sessions of the same user are untouched.
     */
    public void revokeFamily(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        Entry entry = tokens.get(OpaqueTokens.hash(token));
        if (entry != null) {
            revokeFamily(entry.family());
        }
    }

    private boolean stillAllowed(Entry entry) {
        return !revokedFamilies.containsKey(entry.family()) && entry.epoch() == epoch(entry.userId());
    }

    private long epoch(UUID userId) {
        return userEpochs.getOrDefault(userId, 0L);
    }

    private String put(UUID userId, UUID family, long epoch) {
        String token = OpaqueTokens.generate();
        tokens.put(OpaqueTokens.hash(token),
                new Entry(userId, family, epoch, clock.instant().plus(properties.refreshTokenTtl()), false));
        return token;
    }

    private void revokeFamily(UUID family) {
        revokedFamilies.put(family, clock.instant().plus(properties.refreshTokenTtl()));
        tokens.values().removeIf(e -> e.family().equals(family) && !e.spent());
    }

    /** At most once a minute, from both issue and rotate, so the maps cannot grow without bound. */
    private void purgeExpiredIfDue() {
        Instant now = clock.instant();
        Instant last = lastPurge.get();
        if (now.isBefore(last.plus(PURGE_INTERVAL)) || !lastPurge.compareAndSet(last, now)) {
            return;
        }
        tokens.values().removeIf(e -> !now.isBefore(e.expiresAt()));
        revokedFamilies.values().removeIf(until -> !now.isBefore(until));
    }

    /** Test hook: number of hashes currently held (live and spent). */
    int size() {
        return tokens.size();
    }

    /** Test hook: number of live (unspent) tokens held for a user. */
    long liveCount(UUID userId) {
        return tokens.values().stream().filter(e -> e.userId().equals(userId) && !e.spent()).count();
    }
}
