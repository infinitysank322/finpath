package com.finpath.dashboard;

import com.finpath.dpdp.ConsentRequiredException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Fixed error codes only (docs/api-conventions.md), never exception text. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = DashboardExceptionHandler.class)
class DashboardExceptionHandler {

    @ExceptionHandler(ConsentRequiredException.class)
    ResponseEntity<Map<String, Object>> consentRequired() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", 403, "error", "consent_required"));
    }
}
