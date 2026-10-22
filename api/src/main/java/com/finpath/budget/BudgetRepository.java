package com.finpath.budget;

import com.finpath.rules.BudgetRules.Level;
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
 * Persistence for category_budget. Every method runs in the RLS-bound transaction (it joins one
 * that is already open), so the database confines it to the caller's rows; the owner id must come
 * from the authenticated principal. Amounts are stored and returned as given: no arithmetic here.
 */
@Repository
public class BudgetRepository {

    /** A stored budget line, with the category code resolved. */
    public record Row(UUID id, String categoryCode, long budgetPaise, Level lastAlertLevel) {
    }

    private static final String SELECT = "SELECT b.id, c.code, b.budget_paise, b.last_alert_level "
            + "FROM category_budget b JOIN category c ON c.id = b.category_id ";

    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;

    @Autowired
    public BudgetRepository(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx) {
        this(() -> required(jdbc.getIfAvailable()), () -> required(tx.getIfAvailable()));
    }

    public BudgetRepository(Supplier<JdbcTemplate> jdbc, Supplier<TransactionTemplate> tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private static <T> T required(T bean) {
        if (bean == null) {
            throw new IllegalStateException("No datasource configured");
        }
        return bean;
    }

    /** Runs {@code work} in one RLS transaction; nested repository calls join it. */
    public <T> T inTransaction(java.util.function.Supplier<T> work) {
        return tx.get().execute(status -> work.get());
    }

    /** The user's cycle start day, or empty if the user row is not visible. */
    public Optional<Integer> cycleStartDay(UUID userId) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("SELECT cycle_start_day FROM users WHERE id = ?",
                (rs, n) -> rs.getInt(1), userId).stream().findFirst());
    }

    /** The cycle containing {@code anyDate} under the user's start day (the rules' calendar). */
    public com.finpath.rules.CycleCalendar.Cycle cycleOf(UUID userId, LocalDate anyDate) {
        int startDay = cycleStartDay(userId).orElseThrow(BudgetNotFoundException::new);
        return com.finpath.rules.CycleCalendar.cycleFor(anyDate, startDay);
    }

    /**
     * Creates the line or changes its amount. A different amount resets last_alert_level to OK so the
     * new limit can alert; the same amount keeps it. Returns empty when the category code is unknown.
     */
    public Optional<Row> upsert(UUID userId, LocalDate cycleStart, String categoryCode, long budgetPaise) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> {
            List<UUID> cat = j.queryForList("SELECT id FROM category WHERE code = ?", UUID.class, categoryCode);
            if (cat.isEmpty()) {
                return Optional.<Row>empty();
            }
            UUID id = j.queryForObject("INSERT INTO category_budget (user_id, cycle_start, category_id, "
                    + "budget_paise) VALUES (?, ?, ?, ?) ON CONFLICT (user_id, cycle_start, category_id) "
                    + "DO UPDATE SET budget_paise = EXCLUDED.budget_paise, last_alert_level = CASE WHEN "
                    + "category_budget.budget_paise = EXCLUDED.budget_paise THEN category_budget.last_alert_level "
                    + "ELSE 'OK' END, updated_at = now() RETURNING id", UUID.class, userId, cycleStart,
                    cat.get(0), budgetPaise);
            return j.query(SELECT + "WHERE b.id = ?", (rs, n) -> map(rs), id).stream().findFirst();
        });
    }

    /** True if a line was removed. */
    public boolean delete(UUID userId, LocalDate cycleStart, String categoryCode) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.update("DELETE FROM category_budget WHERE user_id = ? "
                + "AND cycle_start = ? AND category_id = (SELECT id FROM category WHERE code = ?)", userId,
                cycleStart, categoryCode) > 0);
    }

    /** The cycle's lines ordered by category code. */
    public List<Row> listForCycle(UUID userId, LocalDate cycleStart) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query(SELECT + "WHERE b.user_id = ? AND b.cycle_start = ? "
                + "ORDER BY c.code", (rs, n) -> map(rs), userId, cycleStart));
    }

    /**
     * Compare-and-set: moves last_alert_level from {@code expected} to {@code next} only if it still
     * is {@code expected}. True for exactly one of any set of concurrent callers.
     */
    public boolean advanceLevel(UUID userId, UUID id, Level expected, Level next) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.update("UPDATE category_budget SET last_alert_level = ?, "
                + "updated_at = now() WHERE id = ? AND user_id = ? AND last_alert_level = ?", next.name(), id,
                userId, expected.name()) == 1);
    }

    private static Row map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3),
                Level.valueOf(rs.getString(4)));
    }
}
