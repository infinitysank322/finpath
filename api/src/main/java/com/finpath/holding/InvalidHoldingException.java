package com.finpath.holding;

/** Bad holding input; maps to 400 invalid_request. Carries no message on purpose (no amounts or names in errors). */
public class InvalidHoldingException extends RuntimeException {

    public InvalidHoldingException() {
        super(null, null, false, false);
    }
}
