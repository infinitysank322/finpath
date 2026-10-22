package com.finpath.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Startup fails fast on an unsafe auth configuration, and the key is never printed. */
class AuthPropertiesTest {

    private static final String OK_SECRET = "s".repeat(AuthProperties.MIN_SECRET_BYTES);
    private static final List<String> OK_REDIRECTS = List.of("http://localhost:4200/auth/callback");

    private static AuthProperties make(String secret, List<String> redirects, Duration codeTtl) {
        return new AuthProperties(secret, redirects, null, null, codeTtl);
    }

    @Test
    void missingOrShortSecretFailsStartup() {
        assertThatThrownBy(() -> make(null, OK_REDIRECTS, null)).hasMessageContaining("FINPATH_JWT_SECRET");
        assertThatThrownBy(() -> make("", OK_REDIRECTS, null)).hasMessageContaining("FINPATH_JWT_SECRET");
        assertThatThrownBy(() -> make("s".repeat(31), OK_REDIRECTS, null)).hasMessageContaining("32 bytes");
    }

    @Test
    void emptyAllowListFailsStartup() {
        assertThatThrownBy(() -> make(OK_SECRET, List.of(), null))
                .hasMessageContaining("FINPATH_AUTH_ALLOWED_REDIRECTS");
        assertThatThrownBy(() -> make(OK_SECRET, List.of(" "), null))
                .hasMessageContaining("FINPATH_AUTH_ALLOWED_REDIRECTS");
    }

    @Test
    void wildcardRelativeOrFragmentRedirectsAreRejected() {
        assertThatThrownBy(() -> make(OK_SECRET, List.of("https://*.example.com/cb"), null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> make(OK_SECRET, List.of("/auth/callback"), null))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> make(OK_SECRET, List.of("http://localhost:4200/cb#x"), null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void codeTtlAboveSixtySecondsIsRejected() {
        assertThatThrownBy(() -> make(OK_SECRET, OK_REDIRECTS, Duration.ofSeconds(61)))
                .hasMessageContaining("60s");
    }

    @Test
    void defaultsAndTrimming() {
        AuthProperties p = make(OK_SECRET, List.of(" http://localhost:4200/auth/callback ",
                "com.finpath.app://auth/callback"), null);
        assertThat(p.accessTokenTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(p.oneTimeCodeTtl()).isEqualTo(Duration.ofSeconds(60));
        assertThat(p.allowedRedirects()).containsExactly("http://localhost:4200/auth/callback",
                "com.finpath.app://auth/callback");
    }

    @Test
    void toStringRedactsTheSecret() {
        assertThat(make(OK_SECRET, OK_REDIRECTS, null).toString()).doesNotContain(OK_SECRET).contains("redacted");
    }

    @Test
    void allowListIsExactMatch() {
        ReturnUrlAllowList list = new ReturnUrlAllowList(make(OK_SECRET, OK_REDIRECTS, null));
        assertThat(list.isAllowed("http://localhost:4200/auth/callback")).isTrue();
        assertThat(list.isAllowed(null)).isFalse();
        assertThat(list.isAllowed("http://localhost:4200/auth/callback/")).isFalse();
        assertThat(list.isAllowed("http://localhost:4200/auth/callback?x=1")).isFalse();
        assertThat(list.isAllowed("http://localhost:4200/auth/callback.evil.example")).isFalse();
        assertThat(list.isAllowed("HTTP://LOCALHOST:4200/auth/callback")).isFalse();
        assertThat(list.isAllowed("https://evil.example/auth/callback")).isFalse();
    }
}
