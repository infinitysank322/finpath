package com.finpath.expense;

/** An Idempotency-Key was reused with a different payload. */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException() {
        super(null, null, false, false);
    }
}
