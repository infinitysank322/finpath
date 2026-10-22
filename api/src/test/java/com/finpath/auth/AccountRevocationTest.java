package com.finpath.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.finpath.auth.TokenStoresTest.MutableClock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** T31: what DELETE /me does to the in-memory credentials, at unit level with a controllable clock. */
class AccountRevocationTest {

    private final MutableClock clock = new MutableClock();
    private final AuthProperties props = TokenStoresTest.props();
    private final RefreshTokenStore refresh = new RefreshTokenStore(props, clock);
    private final OneTimeCodeStore codes = new OneTimeCodeStore(props, clock);
    private final DeletedUsers deleted = new DeletedUsers(props, clock);
    private final SessionRevoker revoker = new SessionRevoker(refresh, codes, deleted);
    private final String verifier = OpaqueTokens.generate();
    private final String challenge = Pkce.s256(verifier);

    @Test
    void oneTimeCodeRevokeAllDropsOnlyThatUsersLiveCodes() {
        UUID gone = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        String g1 = codes.issue(gone, challenge);
        String g2 = codes.issue(gone, challenge);
        String k = codes.issue(kept, challenge);
        codes.revokeAll(gone);
        assertThat(codes.consume(g1, verifier)).isEmpty();
        assertThat(codes.consume(g2, verifier)).isEmpty();
        assertThat(codes.consume(k, verifier)).contains(kept);
    }

    @Test
    void revokerEndsRefreshTokensCodesAndMarksTheUserDeleted() {
        UUID u = UUID.randomUUID();
        String r = refresh.issue(u);
        String c = codes.issue(u, challenge);
        revoker.revokeAllForDeletedUser(u);
        assertThat(refresh.rotate(r)).isEmpty();
        assertThat(codes.consume(c, verifier)).isEmpty();
        assertThat(deleted.isDeleted(u)).isTrue();
        assertThat(deleted.isDeleted(UUID.randomUUID())).isFalse();
    }

    @Test
    void aRefreshTokenRotatedBeforeDeletionIsAlsoDead() {
        UUID u = UUID.randomUUID();
        String first = refresh.issue(u);
        String second = refresh.rotate(first).orElseThrow().refreshToken();
        revoker.revokeAllForDeletedUser(u);
        assertThat(refresh.rotate(second)).isEmpty();
        assertThat(refresh.liveCount(u)).isZero();
    }

    @Test
    void theDeletedMarkOutlivesTheLongestAccessTokenThenIsDropped() {
        UUID u = UUID.randomUUID();
        deleted.markDeleted(u);
        clock.advance(props.accessTokenTtl().plusSeconds(30));   // token expiry plus decoder skew
        assertThat(deleted.isDeleted(u)).as("a skewed-but-valid token must still be refused").isTrue();
        clock.advance(Duration.ofMinutes(3));
        assertThat(deleted.isDeleted(u)).isFalse();
    }
}
