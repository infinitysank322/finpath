package com.finpath.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;

/**
 * Test support (T32): a logback appender on the root logger at DEBUG, rendering message and stack
 * trace of every event, plus the detectors for what must never reach a log.
 */
public final class LogCapture implements AutoCloseable {

    public static final Pattern EMAIL_PATTERN = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)+");
    public static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "\u20b9|\\bRs\\.?\\s*\\d|\\bINR\\s*\\d|\\d+\\.\\d{2}\\b|\\b\\d{1,3}(,\\d{2,3})+\\b");
    public static final Pattern BEARER_PATTERN = Pattern.compile(
            "(?i)bearer\\s+[A-Za-z0-9._~+/=-]{8,}|eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    private final Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final Level previousLevel = root.getLevel();
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    private LogCapture() {
    }

    public static LogCapture start() {
        LogCapture c = new LogCapture();
        c.root.setLevel(Level.DEBUG);
        c.appender.start();
        c.root.addAppender(c.appender);
        return c;
    }

    /** Logger name, message and rendered stack trace of every event: what a real appender would write. */
    public String text() {
        StringBuilder sb = new StringBuilder();
        for (ILoggingEvent e : List.copyOf(appender.list)) {
            sb.append(e.getLoggerName()).append(' ').append(e.getFormattedMessage()).append('\n');
            if (e.getThrowableProxy() != null) {
                sb.append(ThrowableProxyUtil.asString(e.getThrowableProxy())).append('\n');
            }
        }
        return sb.toString();
    }

    public static void assertClean(String text, List<String> secretValues) {
        assertThat(EMAIL_PATTERN.matcher(text).find()).as("email address in logs:%n%s", text).isFalse();
        assertThat(AMOUNT_PATTERN.matcher(text).find()).as("amount pattern in logs:%n%s", text).isFalse();
        assertThat(BEARER_PATTERN.matcher(text).find()).as("bearer/JWT value in logs:%n%s", text).isFalse();
        assertThat(text).doesNotContain("SWIGGY");
        for (String secret : secretValues) {
            assertThat(text).as("secret value in logs").doesNotContain(secret);
        }
    }

    @Override
    public void close() {
        root.detachAppender(appender);
        root.setLevel(previousLevel);
    }
}
