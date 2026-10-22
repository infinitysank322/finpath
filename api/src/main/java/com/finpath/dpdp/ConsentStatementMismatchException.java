package com.finpath.dpdp;

/** The client consented to a statement that is not the current one (stale version or wrong purpose). */
public class ConsentStatementMismatchException extends RuntimeException {

    public ConsentStatementMismatchException() {
        super("unknown consent statement");
    }
}
