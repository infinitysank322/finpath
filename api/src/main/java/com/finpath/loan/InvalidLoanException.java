package com.finpath.loan;

/** Bad loan input; maps to 400 invalid_request. Carries no message on purpose (no lender or amount in errors). */
public class InvalidLoanException extends RuntimeException {

    public InvalidLoanException() {
        super(null, null, false, false);
    }
}
