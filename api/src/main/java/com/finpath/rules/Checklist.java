package com.finpath.rules;

import java.util.List;
import java.util.Objects;

/** FR-26 checklist rules. Pure; no money. */
public final class Checklist {

    private Checklist() {
    }

    /**
     * A cycle's checklist is complete when it has at least one item and every item is done. A cycle with
     * no items counts as not complete (nothing was planned or tracked, so nothing was achieved).
     */
    public static boolean cycleComplete(List<Boolean> itemDone) {
        Objects.requireNonNull(itemDone, "itemDone");
        if (itemDone.isEmpty()) {
            return false;
        }
        for (Boolean done : itemDone) {
            if (!Objects.requireNonNull(done, "done flag")) {
                return false;
            }
        }
        return true;
    }
}
