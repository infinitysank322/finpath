package com.finpath.expense;

/** No such expense for this user (unknown id or another user's row; RLS makes them indistinguishable). */
public class ExpenseNotFoundException extends RuntimeException {
    public ExpenseNotFoundException() {
        super(null, null, false, false);
    }
}
