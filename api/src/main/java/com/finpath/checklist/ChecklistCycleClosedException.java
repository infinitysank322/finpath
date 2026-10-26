package com.finpath.checklist;

/** The item belongs to a CLOSED cycle and can no longer be ticked; maps to 409 cycle_closed. */
public class ChecklistCycleClosedException extends RuntimeException {

    public ChecklistCycleClosedException() {
        super(null, null, false, false);
    }
}
