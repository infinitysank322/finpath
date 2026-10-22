package com.finpath.dpdp;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Fixed error codes only, never exception text. */
@RestControllerAdvice(basePackageClasses = DpdpExceptionHandler.class)
class DpdpExceptionHandler {

    @ExceptionHandler(ConsentRequiredException.class)
    ResponseEntity<Map<String, Object>> consentRequired() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", 403, "error", "consent_required"));
    }

    @ExceptionHandler(ConsentStatementMismatchException.class)
    ResponseEntity<Map<String, Object>> badRequest() {
        return ResponseEntity.badRequest().body(Map.of("status", 400, "error", "invalid_request"));
    }
}
