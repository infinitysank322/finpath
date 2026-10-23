package com.finpath.allocation;

import com.finpath.api.ErrorCode;
import com.finpath.dpdp.ConsentRequiredException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Fixed error codes only, never exception text. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = AllocationExceptionHandler.class)
class AllocationExceptionHandler {

    @ExceptionHandler(InvalidAllocationException.class)
    ResponseEntity<Map<String, Object>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("status", 400, "error", ErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(ConsentRequiredException.class)
    ResponseEntity<Map<String, Object>> consentRequired() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", 403, "error", "consent_required"));
    }

    @ExceptionHandler(AllocationNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", 404, "error", ErrorCode.NOT_FOUND));
    }
}
