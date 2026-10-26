package com.finpath.loan;

import java.math.BigDecimal;
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
 * Persistence for loan. Every method runs in the RLS-bound transaction, so the database confines it
 * to the caller's rows; the owner id must come from the authenticated principal. Values are stored
 * and returned as given: no arithmetic here, and nothing is logged.
 */
@Repository
public class LoanRepository {

    /** A loan row as stored. */
    public record Row(UUID id, String lender, String type, long principalOutstandingPaise, BigDecimal rate,
            Long emiPaise, boolean emiDerived, Integer emisLeft, LocalDate bulletDueDate, long upfrontFeePaise,
            Instant createdAt, Integer dueDay) {
    }

    /** Values to write; the id and timestamps are the database's. */
    public record Draft(String lender, String type, long principalOutstandingPaise, BigDecimal rate,
            Long emiPaise, boolean emiDerived, Integer emisLeft, LocalDate bulletDueDate, long upfrontFeePaise, Integer dueDay) {
    }

    private static final String COLUMNS = "id, lender, type, principal_outstanding_paise, rate, emi_paise, "
            + "emi_derived, emis_left, bullet_due_date, upfront_fee_paise, created_at, due_day";

    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;

    @Autowired
    public LoanRepository(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx) {
        this(() -> required(jdbc.getIfAvailable()), () -> required(tx.getIfAvailable()));
    }

    public LoanRepository(Supplier<JdbcTemplate> jdbc, Supplier<TransactionTemplate> tx) {
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
        return tx.get().execute(status -> j.queryForObject("INSERT INTO loan (user_id, lender, type, "
                + "principal_outstanding_paise, rate, emi_paise, emi_derived, emis_left, bullet_due_date, "
                + "upfront_fee_paise, due_day) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
                (rs, n) -> map(rs), userId, d.lender(), d.type(), d.principalOutstandingPaise(), d.rate(),
                d.emiPaise(), d.emiDerived(), d.emisLeft(), d.bulletDueDate(), d.upfrontFeePaise(), d.dueDay()));
    }

    public Optional<Row> update(UUID userId, UUID id, Draft d) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> {
            Optional<Row> row = j.query("UPDATE loan SET lender = ?, type = ?, "
                    + "principal_outstanding_paise = ?, rate = ?, emi_paise = ?, emi_derived = ?, emis_left = ?, "
                    + "bullet_due_date = ?, upfront_fee_paise = ?, due_day = ?, updated_at = now() WHERE id = ? AND user_id = ? "
                    + "RETURNING " + COLUMNS, (rs, n) -> map(rs), d.lender(), d.type(),
                    d.principalOutstandingPaise(), d.rate(), d.emiPaise(), d.emiDerived(), d.emisLeft(),
                    d.bulletDueDate(), d.upfrontFeePaise(), d.dueDay(), id, userId).stream().findFirst();
            if (row.isPresent()) {
                syncOpenChecklistItems(j, userId, id, d);
            }
            return row;
        });
    }

    /**
     * Keeps the loan's checklist items in step with an edit: items still open (not done, cycle OPEN) take the
     * new EMI, or are dropped when the loan no longer has an EMI or is paid off. Done items and closed cycles
     * stay frozen (the V14 trigger also enforces this).
     */
    private static void syncOpenChecklistItems(JdbcTemplate j, UUID userId, UUID id, Draft d) {
        String open = " FROM plan_cycle p WHERE p.id = i.plan_cycle_id AND p.status = 'OPEN' AND i.type = 'EMI' "
                + "AND i.source_ref = ? AND i.user_id = ? AND i.done_at IS NULL";
        if (d.emiPaise() == null || d.emiPaise() <= 0 || d.principalOutstandingPaise() == 0) {
            j.update("DELETE FROM checklist_item i USING plan_cycle p WHERE p.id = i.plan_cycle_id "
                    + "AND p.status = 'OPEN' AND i.type = 'EMI' AND i.source_ref = ? AND i.user_id = ? "
                    + "AND i.done_at IS NULL", id.toString(), userId);
        } else {
            j.update("UPDATE checklist_item i SET amount_paise = ?" + open, d.emiPaise(), id.toString(), userId);
        }
    }

    public Optional<Row> find(UUID userId, UUID id) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("SELECT " + COLUMNS + " FROM loan WHERE id = ? AND user_id = ?",
                (rs, n) -> map(rs), id, userId).stream().findFirst());
    }

    /** The user's cycle start day, or empty if the user row is not visible. */
    public Optional<Integer> cycleStartDay(UUID userId) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("SELECT cycle_start_day FROM users WHERE id = ?",
                (rs, n) -> rs.getInt(1), userId).stream().findFirst());
    }

    /** The user's loans ordered by type, then creation time. */
    public List<Row> list(UUID userId) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("SELECT " + COLUMNS + " FROM loan WHERE user_id = ? "
                + "ORDER BY type, created_at, id", (rs, n) -> map(rs), userId));
    }

    /** True if a loan was removed. */
    public boolean delete(UUID userId, UUID id) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.update("DELETE FROM loan WHERE id = ? AND user_id = ?", id, userId) > 0);
    }

    private static Row map(ResultSet rs) throws SQLException {
        return new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4),
                rs.getBigDecimal(5), (Long) rs.getObject(6), rs.getBoolean(7), (Integer) rs.getObject(8),
                rs.getObject(9, LocalDate.class), rs.getLong(10), rs.getTimestamp(11).toInstant(),
                rs.getObject(12) == null ? null : rs.getInt(12));
    }
}
