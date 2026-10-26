package com.finpath.reminder;

import java.util.UUID;

/**
 * What a reminder says. Text is a fixed constant per kind: no amount, lender, merchant, category or digit
 * is ever interpolated, so a notification (push, email, log) cannot leak anything about the user's money.
 */
public record ReminderMessage(UUID userId, Kind kind, String title, String body) {

    public enum Kind {
        LOAN_DUE("A loan payment is coming up", "Open FinPath to check this month's payment"),
        CYCLE_START("Your new budget cycle has started", "Plan your month in FinPath");

        private final String title;
        private final String body;

        Kind(String title, String body) {
            this.title = title;
            this.body = body;
        }

        public String title() {
            return title;
        }

        public String body() {
            return body;
        }
    }

    public static ReminderMessage of(UUID userId, Kind kind) {
        return new ReminderMessage(userId, kind, kind.title(), kind.body());
    }
}
