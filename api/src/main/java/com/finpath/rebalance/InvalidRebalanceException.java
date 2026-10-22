package com.finpath.rebalance;

/** Bad request body (missing or non-positive amount); maps to 400 invalid_request. No message on purpose. */
public class InvalidRebalanceException extends RuntimeException {

    public InvalidRebalanceException() {
        super(null, null, false, false);
    }
}
