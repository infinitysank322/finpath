package com.finpath.reminder;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persistence for the reminder job. Except {@link #consentedUsers()}, every method runs in the RLS-bound
 * transaction of the user the caller scoped with {@code RlsScope.runAs}, so the database confines it to that
 * user's rows. Methods are not final so tests can decorate them.
 */
@Repository
public class ReminderRepository {

    /** A loan with a due day that is still open. */
    public record DueLoan(UUID id, int dueDay) {
    }

    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;

    @Autowired
    public ReminderRepository(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx) {
        this(() -> required(jdbc.getIfAvailable()), () -> required(tx.getIfAvailable()));
    }

    public ReminderRepository(Supplier<JdbcTemplate> jdbc, Supplier<TransactionTemplate> tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private static <T> T required(T bean) {
        if (bean == null) {
            throw new IllegalStateException("No datasource configured");
        }
        return bean;
    }

    /** Ids of users with a live consent, via the SECURITY DEFINER function; ids only. Runs outside a transaction. */
    public List<UUID> consentedUsers() {
        return jdbc.get().queryForList("SELECT unnest(finpath_reminder_users())", UUID.class);
    }

    /** True when the scoped user holds a live consent to the current statement. */
    public boolean hasConsent() {
        JdbcTemplate j = jdbc.get();
        return Boolean.TRUE.equals(tx.get().execute(status -> j.queryForObject("SELECT finpath_has_consent()",
                Boolean.class)));
    }

    public List<DueLoan> dueLoans(UUID userId) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("SELECT id, due_day FROM loan WHERE user_id = ? "
                + "AND due_day IS NOT NULL AND principal_outstanding_paise > 0 ORDER BY created_at, id",
                (rs, n) -> new DueLoan(rs.getObject(1, UUID.class), rs.getInt(2)), userId));
    }

    public Optional<Integer> cycleStartDay(UUID userId) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("SELECT cycle_start_day FROM users WHERE id = ?",
                (rs, n) -> rs.getInt(1), userId).stream().findFirst());
    }

    /** True when this call inserted the row, i.e. this run owns the send; false when already claimed. */
    public boolean claim(UUID userId, String key, LocalDate dueDate) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.update("INSERT INTO reminder_sent (user_id, reminder_key, due_date) "
                + "VALUES (?, ?, ?) ON CONFLICT (user_id, reminder_key, due_date) DO NOTHING",
                userId, key, dueDate) == 1);
    }

    /** Gives a claim back after a failed delivery so the next run retries. */
    public void release(UUID userId, String key, LocalDate dueDate) {
        JdbcTemplate j = jdbc.get();
        tx.get().executeWithoutResult(status -> j.update("DELETE FROM reminder_sent WHERE user_id = ? "
                + "AND reminder_key = ? AND due_date = ?", userId, key, dueDate));
    }
}
