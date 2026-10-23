package com.finpath.checklist;

/** No such checklist item or cycle for the caller (unknown id or another user's row); maps to 404 not_found. */
public class ChecklistNotFoundException extends RuntimeException {

    public ChecklistNotFoundException() {
        super(null, null, false, false);
    }
}
