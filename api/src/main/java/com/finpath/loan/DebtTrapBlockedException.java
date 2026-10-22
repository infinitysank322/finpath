package com.finpath.loan;

/** The debt-trap guard returned BLOCK for a new loan and the client has not confirmed. */
class DebtTrapBlockedException extends RuntimeException {

    private final transient LoanService.GuardView guard;

    DebtTrapBlockedException(LoanService.GuardView guard) {
        super("debt_trap_blocked", null, false, false);
        this.guard = guard;
    }

    LoanService.GuardView guard() {
        return guard;
    }
}
