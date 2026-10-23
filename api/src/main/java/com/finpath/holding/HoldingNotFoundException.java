package com.finpath.holding;

/** No such holding for the caller (unknown id or another user's row); maps to 404 not_found. */
public class HoldingNotFoundException extends RuntimeException {

    public HoldingNotFoundException() {
        super(null, null, false, false);
    }
}
