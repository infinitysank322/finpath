package com.finpath.reminder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Default sink until delivery exists: logs the kind only, never the user id or the text. */
@Component
public class LoggingReminderSink implements ReminderSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingReminderSink.class);

    @Override
    public void send(ReminderMessage message) {
        log.info("reminder due: {}", message.kind());
    }
}
