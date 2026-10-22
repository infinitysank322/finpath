package com.finpath.auth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.regex.Pattern;

/**
 * What the client asked for when it started a Google sign-in, validated once here and used by both
 * {@link ReturnUrlGuardFilter} and {@link ReturnUrlAuthorizationRequestResolver}.
 *
 * <p>Query parameters on {@code GET /oauth2/authorization/google}:
 * <ul>
 *   <li>{@code redirect_uri}: required, exactly once, exact match on the allow-list.</li>
 *   <li>{@code code_challenge}: required, base64url SHA-256 of the client's code verifier (43 chars).</li>
 *   <li>{@code code_challenge_method}: required and must be {@code S256}; a missing method is
 *       rejected, never defaulted (RFC 7636's default would be {@code plain}).</li>
 *   <li>{@code state}: optional, 1-128 chars of {@code [A-Za-z0-9._~-]}, echoed back verbatim on the
 *       return redirect for the client to check.</li>
 * </ul>
 *
 * @param clientState null when the client sent none
 */
record PendingLogin(String returnUrl, String codeChallenge, String clientState) {

    static final String REDIRECT_URI = ReturnUrlAllowList.PARAM;
    static final String CODE_CHALLENGE = "code_challenge";
    static final String CODE_CHALLENGE_METHOD = "code_challenge_method";
    static final String STATE = "state";
    static final String S256 = "S256";

    private static final Pattern CHALLENGE = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final Pattern CLIENT_STATE = Pattern.compile("[A-Za-z0-9._~-]{1,128}");

    /** Rejected sign-in start; {@code error} is a fixed code for the 400 body. */
    static final class Rejected extends RuntimeException {
        private final String error;

        Rejected(String error) {
            super(error, null, false, false);
            this.error = error;
        }

        String error() {
            return error;
        }
    }

    static PendingLogin parse(HttpServletRequest request, ReturnUrlAllowList allowList) {
        String returnUrl = single(request, REDIRECT_URI, "invalid_redirect");
        if (!allowList.isAllowed(returnUrl)) {
            throw new Rejected("invalid_redirect");
        }
        String challenge = single(request, CODE_CHALLENGE, "invalid_request");
        String method = single(request, CODE_CHALLENGE_METHOD, "invalid_request");
        if (!CHALLENGE.matcher(challenge).matches() || !S256.equals(method)) {
            throw new Rejected("invalid_request");
        }
        String[] states = request.getParameterValues(STATE);
        String state = null;
        if (states != null) {
            if (states.length != 1 || !CLIENT_STATE.matcher(states[0]).matches()) {
                throw new Rejected("invalid_request");
            }
            state = states[0];
        }
        return new PendingLogin(returnUrl, challenge, state);
    }

    /** Re-check after the round trip through the session. */
    boolean stillValid(ReturnUrlAllowList allowList) {
        return allowList.isAllowed(returnUrl) && codeChallenge != null && CHALLENGE.matcher(codeChallenge).matches()
                && (clientState == null || CLIENT_STATE.matcher(clientState).matches());
    }

    /** The allow-listed URL verbatim plus fixed codes / base64url values / the validated state. */
    String redirect(String name, String value) {
        StringBuilder url = new StringBuilder(returnUrl).append(returnUrl.contains("?") ? '&' : '?')
                .append(name).append('=').append(value);
        if (clientState != null) {
            url.append("&state=").append(clientState);
        }
        return url.toString();
    }

    private static String single(HttpServletRequest request, String name, String error) {
        String[] values = request.getParameterValues(name);
        if (values == null || values.length != 1 || values[0] == null) {
            throw new Rejected(error);
        }
        return values[0];
    }
}
