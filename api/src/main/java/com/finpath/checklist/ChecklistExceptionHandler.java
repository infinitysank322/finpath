package com.finpath.checklist;

import com.finpath.api.ErrorCode;
import com.finpath.dpdp.ConsentRequiredException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Fixed error codes only (docs/api-conventions.md), never exception text; database errors log the class only. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = ChecklistExceptionHandler.class)
class ChecklistExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ChecklistExceptionHandler.class);

    @ExceptionHandler(ConsentRequiredException.class)
    ResponseEntity<Map<String, Object>> consentRequired() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", 403, "error", "consent_required"));
    }

    @ExceptionHandler(ChecklistNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", 404, "error", ErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, Object>> dataAccess(DataAccessException ex) {
        log.error("Checklist data access failed ({})", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("status", 500, "error", ErrorCode.SERVER_ERROR));
    }
}
