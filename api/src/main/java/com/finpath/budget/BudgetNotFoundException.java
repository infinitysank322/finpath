package com.finpath.budget;

/** No such budget line for the caller; maps to 404 not_found. Carries no message on purpose. */
public class BudgetNotFoundException extends RuntimeException {

    public BudgetNotFoundException() {
        super(null, null, false, false);
    }
}
