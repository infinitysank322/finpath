package com.finpath.allocation;

/** Bad allocation input; maps to 400 invalid_request with no detail. */
public class InvalidAllocationException extends RuntimeException {
    public InvalidAllocationException() {
        super(null, null, false, false);
    }
}
