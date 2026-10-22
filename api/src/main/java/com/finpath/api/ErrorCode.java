package com.finpath.api;

import org.springframework.http.HttpStatusCode;

/**
 * The closed set of values the {@code error} field of an error body can take. Clients switch on
 * these; they never change meaning and are never derived from exception text or user input.
 * Codes owned by a feature ({@code consent_required}, {@code invalid_grant}, {@code login_failed}
 * and the like) are fixed strings in that feature and are listed in docs/api-conventions.md.
 */
public final class ErrorCode {

    public static final String INVALID_REQUEST = "invalid_request";
    public static final String UNAUTHORIZED = "unauthorized";
    public static final String INVALID_TOKEN = "invalid_token";
    public static final String FORBIDDEN = "forbidden";
    public static final String NOT_FOUND = "not_found";
    public static final String METHOD_NOT_ALLOWED = "method_not_allowed";
    public static final String NOT_ACCEPTABLE = "not_acceptable";
    public static final String UNSUPPORTED_MEDIA_TYPE = "unsupported_media_type";
    public static final String SERVER_ERROR = "server_error";

    private ErrorCode() {
    }

    /** The generic code for an HTTP status that no feature-specific code claims. */
    public static String forStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status.is5xxServerError() ? SERVER_ERROR : INVALID_REQUEST;
        };
    }
}
