package com.finpath.recurring;

/** The request failed validation. Carries no message: error bodies and logs never echo input. */
public class InvalidRecurringException extends RuntimeException {
    public InvalidRecurringException() {
        super(null, null, false, false);
    }
}
