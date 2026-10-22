package com.finpath.recurring;

/** No such template for this user (unknown id or another user's row; RLS makes them indistinguishable). */
public class RecurringNotFoundException extends RuntimeException {
    public RecurringNotFoundException() {
        super(null, null, false, false);
    }
}
