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
            Instant createdAt) {
    }

    /** Values to write; the id and timestamps are the database's. */
    public record Draft(String lender, String type, long principalOutstandingPaise, BigDecimal rate,
            Long emiPaise, boolean emiDerived, Integer emisLeft, LocalDate bulletDueDate, long upfrontFeePaise) {
    }

    private static final String COLUMNS = "id, lender, type, principal_outstanding_paise, rate, emi_paise, "
            + "emi_derived, emis_left, bullet_due_date, upfront_fee_paise, created_at";

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
                + "upfront_fee_paise) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING " + COLUMNS,
                (rs, n) -> map(rs), userId, d.lender(), d.type(), d.principalOutstandingPaise(), d.rate(),
                d.emiPaise(), d.emiDerived(), d.emisLeft(), d.bulletDueDate(), d.upfrontFeePaise()));
    }

    public Optional<Row> update(UUID userId, UUID id, Draft d) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("UPDATE loan SET lender = ?, type = ?, "
                + "principal_outstanding_paise = ?, rate = ?, emi_paise = ?, emi_derived = ?, emis_left = ?, "
                + "bullet_due_date = ?, upfront_fee_paise = ?, updated_at = now() WHERE id = ? AND user_id = ? "
                + "RETURNING " + COLUMNS, (rs, n) -> map(rs), d.lender(), d.type(),
                d.principalOutstandingPaise(), d.rate(), d.emiPaise(), d.emiDerived(), d.emisLeft(),
                d.bulletDueDate(), d.upfrontFeePaise(), id, userId).stream().findFirst());
    }

    public Optional<Row> find(UUID userId, UUID id) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("SELECT " + COLUMNS + " FROM loan WHERE id = ? AND user_id = ?",
                (rs, n) -> map(rs), id, userId).stream().findFirst());
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
                rs.getObject(9, LocalDate.class), rs.getLong(10), rs.getTimestamp(11).toInstant());
    }
}
