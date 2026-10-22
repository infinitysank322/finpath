package com.finpath.dpdp;

/**
 * The purpose statement users consent to. A change of wording needs a new version; users who
 * agreed to an older version are asked again (their row names the version they saw).
 */
public record ConsentStatement(String purpose, String version, String text) {

    public static final ConsentStatement CURRENT = new ConsentStatement(
            "FINANCIAL_PLANNING",
            "2026-10-v2",
            "FinPath will store your name and email address as provided by Google sign-in, your risk profile "
                    + "and financial-independence target age, the income, expenses, loans, goals and investments "
                    + "you enter, and the amounts, dates and merchant names parsed from statements or messages you "
                    + "import, only to plan your budget, goals and net worth for you. Account numbers, card numbers "
                    + "and identity documents are never stored. Only aggregated, de-identified figures may be sent "
                    + "to an AI service, and only if you turn it on. You can export all your data at any time. If "
                    + "you withdraw this consent, your account and all its data are kept for 7 days and then "
                    + "permanently deleted; to erase everything immediately, use DELETE /me (Delete my account).");
}
