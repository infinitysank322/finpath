package com.finpath.plan;

/** The authenticated user has no visible users row (for example, the account was deleted). */
public class PlanUserNotFoundException extends RuntimeException {

    public PlanUserNotFoundException() {
        super("user not found");
    }
}
