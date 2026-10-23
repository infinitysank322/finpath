package com.finpath.fi;

/** Bad FI planner input; mapped to a fixed 400 with no detail. */
public class InvalidFiException extends RuntimeException {
    public InvalidFiException() {
        super(null, null, false, false);
    }
}
