package com.finpath.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

/**
 * Session-backed store for the in-flight authorization request (the only session use in the app,
 * ended by the login handlers). When the callback consumes the request, the {@link PendingLogin}
 * saved in it is copied to a request attribute so the success and failure handlers can read it.
 */
final class ReturnUrlExposingAuthorizationRequestRepository
        implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

    static final String REQUEST_ATTRIBUTE = ReturnUrlExposingAuthorizationRequestRepository.class.getName() + ".login";

    private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> delegate;

    ReturnUrlExposingAuthorizationRequestRepository() {
        this(new HttpSessionOAuth2AuthorizationRequestRepository());
    }

    ReturnUrlExposingAuthorizationRequestRepository(AuthorizationRequestRepository<OAuth2AuthorizationRequest> delegate) {
        this.delegate = delegate;
    }

    @Override
    public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
        return delegate.loadAuthorizationRequest(request);
    }

    @Override
    public void saveAuthorizationRequest(OAuth2AuthorizationRequest authorizationRequest, HttpServletRequest request,
            HttpServletResponse response) {
        delegate.saveAuthorizationRequest(authorizationRequest, request, response);
    }

    @Override
    public OAuth2AuthorizationRequest removeAuthorizationRequest(HttpServletRequest request,
            HttpServletResponse response) {
        OAuth2AuthorizationRequest removed = delegate.removeAuthorizationRequest(request, response);
        if (removed != null
                && removed.getAttribute(ReturnUrlAuthorizationRequestResolver.RETURN_URL) instanceof String url) {
            request.setAttribute(REQUEST_ATTRIBUTE, new PendingLogin(url,
                    removed.getAttribute(ReturnUrlAuthorizationRequestResolver.CODE_CHALLENGE),
                    removed.getAttribute(ReturnUrlAuthorizationRequestResolver.CLIENT_STATE)));
        }
        return removed;
    }

    /** The sign-in carried through this callback, or null. Callers must re-validate it. */
    static PendingLogin pendingLogin(HttpServletRequest request) {
        return request.getAttribute(REQUEST_ATTRIBUTE) instanceof PendingLogin p ? p : null;
    }
}
