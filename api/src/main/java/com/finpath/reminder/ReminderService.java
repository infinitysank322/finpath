package com.finpath.reminder;

import com.finpath.reminder.ReminderMessage.Kind;
import com.finpath.reminder.ReminderRepository.DueLoan;
import com.finpath.rls.RlsScope;
import com.finpath.rules.Reminders;
import com.finpath.rules.Reminders.Reminder;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Decides which reminders are due and hands each to the sink exactly once. The dates come from the rules
 * package; "already sent" is the database unique key (claim with ON CONFLICT DO NOTHING), not memory, so a
 * retry after a slow or timed-out first attempt, an overlapping run, or a second instance cannot double-send.
 * Each user is processed in short transactions as that user; one user's failure (including a database that is
 * still waking) is counted and retried next run, never aborts the others. Logs counts only.
 */
@Service
public class ReminderService {

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);

    private final ReminderRepository repo;
    private final ReminderSink sink;
    private final Clock clock;

    @Autowired
    public ReminderService(ReminderRepository repo, ReminderSink sink) {
        this(repo, sink, Clock.system(ZONE));
    }

    public ReminderService(ReminderRepository repo, ReminderSink sink, Clock clock) {
        this.repo = repo;
        this.sink = sink;
        this.clock = clock;
    }

    /** @return how many reminders were handed to the sink in this run */
    public int runOnce() {
        LocalDate today = LocalDate.now(clock.withZone(ZONE));
        List<UUID> users = repo.consentedUsers();
        int sent = 0;
        int failedUsers = 0;
        java.util.Set<String> causes = new java.util.TreeSet<>();
        for (UUID user : users) {
            try {
                sent += RlsScope.runAs(user, () -> forUser(user, today));
            } catch (RuntimeException e) {
                failedUsers++;
                causes.add(e.getClass().getSimpleName());
            }
        }
        if (failedUsers > 0) {
            log.warn("reminders: {} of {} user(s) failed this run, will retry ({})", failedUsers, users.size(), causes);
        }
        if (sent > 0) {
            log.info("reminders: {} handed to the sink", sent);
        }
        return sent;
    }

    private int forUser(UUID user, LocalDate today) {
        if (!repo.hasConsent()) {
            return 0;
        }
        int sent = 0;
        for (DueLoan loan : repo.dueLoans(user)) {
            Reminder r = Reminders.loanDue(loan.dueDay(), today);
            if (!r.remindOn().isAfter(today) && deliver(user, "loan_due:" + loan.id(), r.dueDate(), Kind.LOAN_DUE)) {
                sent++;
            }
        }
        int startDay = repo.cycleStartDay(user).orElse(0);
        if (startDay >= 1) {
            var cycle = Reminders.cycleStart(startDay, today).filter(r -> !r.remindOn().isAfter(today));
            if (cycle.isPresent() && deliver(user, "cycle_start", cycle.get().dueDate(), Kind.CYCLE_START)) {
                sent++;
            }
        }
        return sent;
    }

    /** Claim first; only the run whose insert took effect sends. A failed send gives the claim back. */
    private boolean deliver(UUID user, String key, LocalDate dueDate, Kind kind) {
        if (!repo.claim(user, key, dueDate)) {
            return false;
        }
        try {
            sink.send(ReminderMessage.of(user, kind));
            return true;
        } catch (RuntimeException e) {
            repo.release(user, key, dueDate);
            log.warn("reminders: delivery failed ({}), claim released", kind);
            return false;
        }
    }
}
