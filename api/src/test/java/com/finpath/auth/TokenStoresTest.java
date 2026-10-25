package com.finpath.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

/** One-time code and refresh token lifetimes, PKCE binding, single use and rotation. */
class TokenStoresTest {

    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static AuthProperties props() {
        return new AuthProperties("x".repeat(AuthProperties.MIN_SECRET_BYTES),
                List.of("http://localhost:4200/auth/callback"),
                Duration.ofMinutes(15), Duration.ofDays(30), Duration.ofSeconds(60));
    }

    private final MutableClock clock = new MutableClock();
    private final UUID user = UUID.randomUUID();
    private final String verifier = OpaqueTokens.generate();
    private final String challenge = Pkce.s256(verifier);

    @Test
    void codeIsSingleUse() {
        OneTimeCodeStore codes = new OneTimeCodeStore(props(), clock);
        String code = codes.issue(user, challenge);
        assertThat(codes.consume(code, verifier)).contains(user);
        assertThat(codes.consume(code, verifier)).isEmpty();
    }

    @Test
    void codeExpiresAfterSixtySeconds() {
        OneTimeCodeStore codes = new OneTimeCodeStore(props(), clock);
        String live = codes.issue(user, challenge);
        String stale = codes.issue(user, challenge);
        clock.advance(Duration.ofSeconds(59));
        assertThat(codes.consume(live, verifier)).contains(user);
        clock.advance(Duration.ofSeconds(1));
        assertThat(codes.consume(stale, verifier)).isEmpty();
    }

    @Test
    void wrongOrMissingVerifierFailsAndBurnsTheCode() {
        OneTimeCodeStore codes = new OneTimeCodeStore(props(), clock);
        String code = codes.issue(user, challenge);
        assertThat(codes.consume(code, OpaqueTokens.generate())).isEmpty();
        assertThat(codes.consume(code, verifier)).as("burned by the wrong attempt").isEmpty();

        String other = codes.issue(user, challenge);
        assertThat(codes.consume(other, null)).isEmpty();
        assertThat(codes.consume(other, verifier)).isEmpty();
    }

    @Test
    void verifierMustBeRfc7636Shaped() {
        assertThat(Pkce.matches(verifier, challenge)).isTrue();
        String shortVerifier = "a".repeat(42);
        assertThat(Pkce.matches(shortVerifier, Pkce.s256(shortVerifier))).isFalse();
        assertThat(Pkce.matches(challenge, challenge)).as("plain method is not accepted").isFalse();
    }

    @Test
    void codeCannotBeIssuedWithoutAChallenge() {
        OneTimeCodeStore codes = new OneTimeCodeStore(props(), clock);
        assertThatThrownBy(() -> codes.issue(user, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankOrUnknownCodeIsRejected() {
        OneTimeCodeStore codes = new OneTimeCodeStore(props(), clock);
        assertThat(codes.consume(null, verifier)).isEmpty();
        assertThat(codes.consume("", verifier)).isEmpty();
        assertThat(codes.consume("guess", verifier)).isEmpty();
    }

    @Test
    void codesAreRandomAndUrlSafe() {
        OneTimeCodeStore codes = new OneTimeCodeStore(props(), clock);
        String a = codes.issue(user, challenge);
        assertThat(a).isNotEqualTo(codes.issue(user, challenge)).matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void refreshRotatesAndOldTokenDies() {
        RefreshTokenStore store = new RefreshTokenStore(props(), clock);
        String first = store.issue(user);
        RefreshTokenStore.Rotation r = store.rotate(first).orElseThrow();
        assertThat(r.userId()).isEqualTo(user);
        assertThat(r.refreshToken()).isNotEqualTo(first);
        assertThat(store.rotate(r.refreshToken())).isPresent();
    }

    @Test
    void replayOfRotatedTokenRevokesFamilyButNotOtherSessions() {
        RefreshTokenStore store = new RefreshTokenStore(props(), clock);
        String phone = store.issue(user);
        String laptop = store.issue(user);
        String phoneNext = store.rotate(phone).orElseThrow().refreshToken();

        assertThat(store.rotate(phone)).isEmpty();
        assertThat(store.rotate(phoneNext)).isEmpty();
        assertThat(store.rotate(laptop)).isPresent();
    }

    @Test
    void revokeFamilyKillsTheTokenAndItsSuccessorsButNotOtherSessions() {
        RefreshTokenStore store = new RefreshTokenStore(props(), clock);
        String phone = store.issue(user);
        String laptop = store.issue(user);
        String phoneNext = store.rotate(phone).orElseThrow().refreshToken();

        store.revokeFamily(phone);   // presenting an already-rotated token still revokes the family

        assertThat(store.rotate(phoneNext)).isEmpty();
        assertThat(store.rotate(phone)).isEmpty();
        assertThat(store.rotate(laptop)).isPresent();
    }

    @Test
    void revokeFamilyOfALiveTokenAndIsIdempotentAndQuietForJunk() {
        RefreshTokenStore store = new RefreshTokenStore(props(), clock);
        String token = store.issue(user);
        store.revokeFamily(token);
        store.revokeFamily(token);
        store.revokeFamily("not-a-token");
        store.revokeFamily("");
        store.revokeFamily((String) null);
        assertThat(store.rotate(token)).isEmpty();
        assertThat(store.liveCount(user)).isZero();
    }

    /** Many concurrent presentations of one token: exactly one rotates, and the replay kills its result. */
    @RepeatedTest(20)
    void concurrentReplayRevokesTheFamilyIncludingTheWinnersToken() throws Exception {
        RefreshTokenStore store = new RefreshTokenStore(props(), clock);
        String token = store.issue(user);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Optional<RefreshTokenStore.Rotation>>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return store.rotate(token);
                }));
            }
            start.countDown();
            List<RefreshTokenStore.Rotation> winners = new ArrayList<>();
            for (var f : results) {
                f.get(10, TimeUnit.SECONDS).ifPresent(winners::add);
            }
            assertThat(winners).hasSizeLessThanOrEqualTo(1);
            for (RefreshTokenStore.Rotation w : winners) {
                assertThat(store.rotate(w.refreshToken())).as("family revoked by the replay").isEmpty();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void refreshExpires() {
        RefreshTokenStore store = new RefreshTokenStore(props(), clock);
        String token = store.issue(user);
        clock.advance(Duration.ofDays(30));
        assertThat(store.rotate(token)).isEmpty();
    }

    @Test
    void rotateAlsoPurgesExpiredEntries() {
        RefreshTokenStore store = new RefreshTokenStore(props(), clock);
        for (int i = 0; i < 5; i++) {
            store.issue(UUID.randomUUID());
        }
        assertThat(store.size()).isEqualTo(5);
        clock.advance(Duration.ofDays(31));
        store.rotate("unknown");
        assertThat(store.size()).isZero();
    }

    @Test
    void revokeAllDropsEveryTokenOfTheUser() {
        RefreshTokenStore store = new RefreshTokenStore(props(), clock);
        String a = store.issue(user);
        String other = store.issue(UUID.randomUUID());
        store.revokeAll(user);
        assertThat(store.rotate(a)).isEmpty();
        assertThat(store.rotate(other)).isPresent();
    }

    /** revokeAll lands after the presented token is marked spent but before the new one is stored. */
    @Test
    void revokeAllRacingAnInFlightRotationLeavesNoLiveToken() {
        RefreshTokenStore store = new RefreshTokenStore(props(), clock);
        String token = store.issue(user);
        store.afterMarkSpent = () -> store.revokeAll(user);

        assertThat(store.rotate(token)).isEmpty();
        assertThat(store.liveCount(user)).isZero();
    }

    /** Same race, but revokeAll lands after the new token is stored, before the re-check. */
    @Test
    void revokeAllAfterTheNewTokenIsStoredStillFailsTheRotation() {
        RefreshTokenStore store = new RefreshTokenStore(props(), clock);
        String token = store.issue(user);
        store.afterNewTokenStored = () -> store.revokeAll(user);

        assertThat(store.rotate(token)).as("the caller never receives a token revokeAll has killed").isEmpty();
        assertThat(store.liveCount(user)).isZero();
    }

    @Test
    void revokeAllKillsTokensFromEarlierRotations() {
        RefreshTokenStore store = new RefreshTokenStore(props(), clock);
        String next = store.rotate(store.issue(user)).orElseThrow().refreshToken();
        store.revokeAll(user);
        assertThat(store.rotate(next)).isEmpty();
        assertThat(store.size()).as("spent hashes of the user are dropped too").isZero();
    }

    @Test
    void freshSignInAfterRevokeAllWorks() {
        RefreshTokenStore store = new RefreshTokenStore(props(), clock);
        store.issue(user);
        store.revokeAll(user);
        String fresh = store.issue(user);
        assertThat(store.rotate(fresh)).isPresent();
    }

    @Test
    void blankRefreshIsRejected() {
        RefreshTokenStore store = new RefreshTokenStore(props(), clock);
        assertThat(store.rotate(null)).isEmpty();
        assertThat(store.rotate(" ")).isEmpty();
    }

    @Test
    void storesKeepOnlyHashes() {
        String token = OpaqueTokens.generate();
        assertThat(OpaqueTokens.hash(token)).isNotEqualTo(token).isEqualTo(OpaqueTokens.hash(token));
    }
}
