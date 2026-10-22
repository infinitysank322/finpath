package com.finpath.auth;

import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Where the client may be sent after sign-in. Exact string match against
 * FINPATH_AUTH_ALLOWED_REDIRECTS: no prefixes, no wildcards, no normalisation, so there is no
 * open redirect to argue about.
 */
@Component
public class ReturnUrlAllowList {

    /** Query parameter on {@code /oauth2/authorization/google} naming the client's return URL. */
    public static final String PARAM = "redirect_uri";

    private final Set<String> allowed;

    public ReturnUrlAllowList(AuthProperties properties) {
        this.allowed = Set.copyOf(properties.allowedRedirects());
    }

    public boolean isAllowed(String url) {
        return url != null && allowed.contains(url);
    }
}
