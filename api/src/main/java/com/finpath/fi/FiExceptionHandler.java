package com.finpath.fi;

import com.finpath.api.ErrorCode;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Fixed error codes only, never exception text. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(basePackageClasses = FiExceptionHandler.class)
class FiExceptionHandler {

    @ExceptionHandler(InvalidFiException.class)
    ResponseEntity<Map<String, Object>> invalid() {
        return ResponseEntity.badRequest().body(Map.of("status", 400, "error", ErrorCode.INVALID_REQUEST));
    }
}
