package com.finpath.loan;

/** No such loan for the caller (unknown id or another user's row); maps to 404 not_found. */
public class LoanNotFoundException extends RuntimeException {

    public LoanNotFoundException() {
        super(null, null, false, false);
    }
}
