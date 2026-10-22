package com.finpath.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.regex.Pattern;

/** RFC 7636 S256: the one-time code is only redeemable by the client that started the sign-in. */
final class Pkce {

    /** RFC 7636 section 4.1: 43-128 unreserved characters. */
    private static final Pattern VERIFIER = Pattern.compile("[A-Za-z0-9._~-]{43,128}");

    private Pkce() {
    }

    static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** Constant-time comparison of S256(verifier) with the stored challenge. */
    static boolean matches(String verifier, String challenge) {
        if (verifier == null || challenge == null || !VERIFIER.matcher(verifier).matches()) {
            return false;
        }
        return MessageDigest.isEqual(s256(verifier).getBytes(StandardCharsets.US_ASCII),
                challenge.getBytes(StandardCharsets.US_ASCII));
    }
}
