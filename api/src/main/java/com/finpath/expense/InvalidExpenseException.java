package com.finpath.expense;

/** The request failed validation. Carries no message: error bodies and logs never echo input. */
public class InvalidExpenseException extends RuntimeException {
    public InvalidExpenseException() {
        super(null, null, false, false);
    }
}
