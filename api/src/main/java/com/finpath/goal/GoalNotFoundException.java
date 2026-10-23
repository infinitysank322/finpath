package com.finpath.goal;

/** No such goal for the caller (unknown id or another user's row); maps to 404 not_found. */
public class GoalNotFoundException extends RuntimeException {

    public GoalNotFoundException() {
        super(null, null, false, false);
    }
}
