package com.finpath.goal;

/** Bad goal input; maps to 400 invalid_request. Carries no message on purpose (no amounts in errors). */
public class InvalidGoalException extends RuntimeException {

    public InvalidGoalException() {
        super(null, null, false, false);
    }
}
