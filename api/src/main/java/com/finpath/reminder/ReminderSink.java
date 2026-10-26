package com.finpath.reminder;

/**
 * Where a claimed reminder goes (push or email arrive with T73). An implementation that throws releases the
 * claim, so the next run retries.
 */
public interface ReminderSink {

    void send(ReminderMessage message);
}
