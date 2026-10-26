package com.finpath.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic trigger for {@link ReminderService} ({@code @EnableScheduling} is on UnconsentedUserPurge). A run
 * on a database that is still waking is just slow; a failure is a warning and the next interval retries.
 */
@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final ReminderService service;

    @Autowired
    public ReminderScheduler(ReminderService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${finpath.reminders.interval:15m}",
            initialDelayString = "${finpath.reminders.initial-delay:2m}")
    public void scheduled() {
        try {
            service.runOnce();
        } catch (RuntimeException e) {
            log.warn("reminder run failed ({})", e.getClass().getSimpleName());
        }
    }
}
