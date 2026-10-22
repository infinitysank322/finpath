package com.finpath.rebalance;

/**
 * No open plan for the current cycle, or it has no computed surplus/allocations; maps to 404 not_found.
 * Carries no message on purpose.
 */
public class RebalanceNotFoundException extends RuntimeException {

    public RebalanceNotFoundException() {
        super(null, null, false, false);
    }
}
