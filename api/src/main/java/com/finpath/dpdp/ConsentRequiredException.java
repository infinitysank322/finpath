package com.finpath.dpdp;

/** A business write was attempted before the user gave (or after they withdrew) consent. */
public class ConsentRequiredException extends RuntimeException {

    public ConsentRequiredException() {
        super("consent required");
    }
}
