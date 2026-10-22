package com.finpath.income;

/** The request body failed validation. Carries no message: error bodies never echo input. */
public class InvalidIncomeException extends RuntimeException {
    public InvalidIncomeException() {
        super(null, null, false, false);
    }
}
