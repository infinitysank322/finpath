package com.finpath.loan;

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
@RestControllerAdvice(basePackageClasses = LoanExceptionHandler.class)
class LoanExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LoanExceptionHandler.class);

    @ExceptionHandler(InvalidLoanException.class)
    ResponseEntity<Map<String, Object>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("status", 400, "error", ErrorCode.INVALID_REQUEST));
    }

    @ExceptionHandler(DebtTrapBlockedException.class)
    ResponseEntity<Map<String, Object>> debtTrap(DebtTrapBlockedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("status", 409, "error", "debt_trap_blocked", "guard", ex.guard()));
    }

    @ExceptionHandler(ConsentRequiredException.class)
    ResponseEntity<Map<String, Object>> consentRequired() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("status", 403, "error", "consent_required"));
    }

    @ExceptionHandler({LoanNotFoundException.class, com.finpath.income.IncomeNotFoundException.class})
    ResponseEntity<Map<String, Object>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("status", 404, "error", ErrorCode.NOT_FOUND));
    }

    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<Map<String, Object>> dataAccess(DataAccessException ex) {
        log.error("Loan data access failed ({})", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("status", 500, "error", ErrorCode.SERVER_ERROR));
    }
}
