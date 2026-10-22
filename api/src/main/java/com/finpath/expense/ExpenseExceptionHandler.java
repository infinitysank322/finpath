package com.finpath.expense;

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

/**
 * Fixed error codes only (docs/api-conventions.md), never exception text. Constraint-violation
 * messages carry the failing row (including the note), so database exceptions are logged by class
 * only and answered with a generic body.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = ExpenseExceptionHandler.class)
class ExpenseExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ExpenseExceptionHandler.class);

    @ExceptionHandler(InvalidExpenseException.class)
    ResponseEntity<Map<String, Object>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("status", 400, "error", ErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(ConsentRequiredException.class)
    ResponseEntity<Map<String, Object>> consentRequired() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", 403, "error", "consent_required"));
    }

    @ExceptionHandler(ExpenseNotFoundException.class)
    ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", 404, "error", ErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<Map<String, Object>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("status", 409, "error", "idempotency_conflict"));
    }

    /** Includes DataIntegrityViolationException: never log or echo its message. */
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, Object>> dataAccess(DataAccessException ex) {
        log.error("Expense data access failed ({})", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("status", 500, "error", ErrorCode.SERVER_ERROR));
    }
}
