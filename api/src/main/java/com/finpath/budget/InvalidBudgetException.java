package com.finpath.budget;

/** Bad category, amount or date; maps to 400 invalid_request. Carries no message on purpose. */
public class InvalidBudgetException extends RuntimeException {

    public InvalidBudgetException() {
        super(null, null, false, false);
    }
}
