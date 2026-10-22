package com.finpath.plan;

/** The requested cycle start day is missing or outside 1..31. Carries no user input. */
public class InvalidStartDayException extends RuntimeException {

    public InvalidStartDayException() {
        super("invalid cycle start day");
    }
}
