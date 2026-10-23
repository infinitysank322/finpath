package com.finpath.goal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
 * Persistence for goal. Every method runs in the RLS-bound transaction, so the database confines it
 * to the caller's rows; the owner id must come from the authenticated principal. Values are stored
 * and returned as given: no arithmetic here, and nothing is logged.
 */
@Repository
public class GoalRepository {

    /** A goal row as stored. */
    public record Row(UUID id, String type, long targetAmountPaise, LocalDate targetDate, long currentAmountPaise,
            int priority, Instant createdAt) {
    }

    /** Values to write; the id and timestamps are the database's. */
    public record Draft(String type, long targetAmountPaise, LocalDate targetDate, long currentAmountPaise,
            int priority) {
    }

    private static final String COLUMNS = "id, type, target_amount_paise, target_date, current_amount_paise, "
            + "priority, created_at";

    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;

    @Autowired
    public GoalRepository(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx) {
        this(() -> required(jdbc.getIfAvailable()), () -> required(tx.getIfAvailable()));
    }

    public GoalRepository(Supplier<JdbcTemplate> jdbc, Supplier<TransactionTemplate> tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private static <T> T required(T bean) {
        if (bean == null) {
            throw new IllegalStateException("No datasource configured");
        }
        return bean;
    }

    public Row insert(UUID userId, Draft d) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.queryForObject("INSERT INTO goal (user_id, type, target_amount_paise, "
                + "target_date, current_amount_paise, priority) VALUES (?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
                (rs, n) -> map(rs), userId, d.type(), d.targetAmountPaise(), d.targetDate(),
                d.currentAmountPaise(), d.priority()));
    }

    public Optional<Row> update(UUID userId, UUID id, Draft d) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("UPDATE goal SET type = ?, target_amount_paise = ?, "
                + "target_date = ?, current_amount_paise = ?, priority = ?, updated_at = now() "
                + "WHERE id = ? AND user_id = ? RETURNING " + COLUMNS, (rs, n) -> map(rs), d.type(),
                d.targetAmountPaise(), d.targetDate(), d.currentAmountPaise(), d.priority(), id, userId)
                .stream().findFirst());
    }

    public Optional<Row> find(UUID userId, UUID id) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("SELECT " + COLUMNS + " FROM goal WHERE id = ? AND user_id = ?",
                (rs, n) -> map(rs), id, userId).stream().findFirst());
    }

    /** The user's cycle start day, or empty if the user row is not visible. */
    public Optional<Integer> cycleStartDay(UUID userId) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("SELECT cycle_start_day FROM users WHERE id = ?",
                (rs, n) -> rs.getInt(1), userId).stream().findFirst());
    }

    /** The user's goals by priority (1 first), then target date. */
    public List<Row> list(UUID userId) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("SELECT " + COLUMNS + " FROM goal WHERE user_id = ? "
                + "ORDER BY priority, target_date, created_at, id", (rs, n) -> map(rs), userId));
    }

    /** True if a goal was removed. */
    public boolean delete(UUID userId, UUID id) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.update("DELETE FROM goal WHERE id = ? AND user_id = ?", id, userId) > 0);
    }

    private static Row map(ResultSet rs) throws SQLException {
        return new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3), rs.getObject(4, LocalDate.class),
                rs.getLong(5), rs.getInt(6), rs.getTimestamp(7).toInstant());
    }
}
