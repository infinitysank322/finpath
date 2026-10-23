package com.finpath.allocation;

/** No open cycle row for the caller; maps to 404. */
public class AllocationNotFoundException extends RuntimeException {
    public AllocationNotFoundException() {
        super(null, null, false, false);
    }
}
