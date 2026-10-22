package com.finpath.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Last-resort error mapping for every controller. Spring MVC's own exceptions (bad JSON, missing
 * parameter, unknown route, wrong method, wrong media type) keep their 4xx status but get the
 * standard {@link ApiError} body instead of a ProblemDetail that echoes request detail. Anything
 * else is a 500 with a generic body; the log line carries the exception class only, because
 * messages and stack frames can contain SQL parameters, amounts and merchant text.
 * Feature handlers (for example the DPDP one) run first.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unhandled(Exception ex) throws Exception {
        // Security owns 401/403 (JSON entry point and access-denied handler); do not turn them into 500s.
        if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
            throw ex;
        }
        log.error("Unhandled exception ({})", ex.getClass().getSimpleName());
        return json(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        if (statusCode.is5xxServerError()) {
            log.error("Request failed ({})", ex.getClass().getSimpleName());
        }
        HttpHeaders out = new HttpHeaders();
        out.putAll(headers);   // keep Allow, Accept and the like
        out.setContentType(MediaType.APPLICATION_JSON);
        return new ResponseEntity<>(ApiError.of(statusCode), out, statusCode);
    }

    private static ResponseEntity<ApiError> json(HttpStatus status) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(ApiError.of(status));
    }
}
