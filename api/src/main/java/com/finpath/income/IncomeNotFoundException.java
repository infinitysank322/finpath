package com.finpath.income;

/** No such row for this user (unknown id or another user's row; RLS makes them indistinguishable). */
public class IncomeNotFoundException extends RuntimeException {
    public IncomeNotFoundException() {
        super(null, null, false, false);
    }
}
