package com.finpath.rules;

/** The three loan shapes, each with different maths. Never infer one from another. */
public enum LoanType {
    /** Amortising: level instalments of principal plus interest. */
    EMI,
    /** Interest-only until a due date, then the whole principal. */
    BULLET,
    /** Card purchase converted to instalments, usually with an upfront fee. */
    CARD_EMI
}
