package com.finpath.api;

import org.springframework.http.HttpStatusCode;

/**
 * The one JSON error body: {@code {"status":<http status>,"error":"<fixed code>"}}. Deliberately
 * no message, detail, path, field name or exception text: those can carry amounts, merchant text
 * or personal data echoed from the request.
 */
public record ApiError(int status, String error) {

    public static ApiError of(HttpStatusCode status) {
        return new ApiError(status.value(), ErrorCode.forStatus(status));
    }
}
