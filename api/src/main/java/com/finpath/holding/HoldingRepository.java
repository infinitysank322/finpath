package com.finpath.holding;

import com.finpath.rules.HoldingTotals.ClassTotal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
 * Persistence for holding. Every method runs in the RLS-bound transaction, so the database confines it to the
 * caller's rows; the owner id must come from the authenticated principal. Values are stored and returned as
 * given, and nothing is logged. It does no arithmetic.
 */
@Repository
public class HoldingRepository {

    /** A holding row as stored. */
    public record Row(UUID id, String assetClass, String name, long investedPaise, long currentValuePaise,
            long monthlyContributionPaise, Instant createdAt) {
    }

    /** Values to write; the id and timestamps are the database's. */
    public record Draft(String assetClass, String name, long investedPaise, long currentValuePaise,
            long monthlyContributionPaise) {
    }

    private static final String COLUMNS = "id, asset_class, name, invested_paise, current_value_paise, "
            + "monthly_contribution_paise, created_at";

    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;

    @Autowired
    public HoldingRepository(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx) {
        this(() -> required(jdbc.getIfAvailable()), () -> required(tx.getIfAvailable()));
    }

    public HoldingRepository(Supplier<JdbcTemplate> jdbc, Supplier<TransactionTemplate> tx) {
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
        return tx.get().execute(status -> j.queryForObject("INSERT INTO holding (user_id, asset_class, name, "
                + "invested_paise, current_value_paise, monthly_contribution_paise) VALUES (?, ?, ?, ?, ?, ?) "
                + "RETURNING " + COLUMNS, (rs, n) -> map(rs), userId, d.assetClass(), d.name(), d.investedPaise(),
                d.currentValuePaise(), d.monthlyContributionPaise()));
    }

    public Optional<Row> update(UUID userId, UUID id, Draft d) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("UPDATE holding SET asset_class = ?, name = ?, "
                + "invested_paise = ?, current_value_paise = ?, monthly_contribution_paise = ?, updated_at = now() "
                + "WHERE id = ? AND user_id = ? RETURNING " + COLUMNS, (rs, n) -> map(rs), d.assetClass(),
                d.name(), d.investedPaise(), d.currentValuePaise(), d.monthlyContributionPaise(), id, userId)
                .stream().findFirst());
    }

    public Optional<Row> find(UUID userId, UUID id) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("SELECT " + COLUMNS + " FROM holding WHERE id = ? AND user_id = ?",
                (rs, n) -> map(rs), id, userId).stream().findFirst());
    }

    /** The user's holdings by asset class, then creation order. */
    public List<Row> list(UUID userId) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("SELECT " + COLUMNS + " FROM holding WHERE user_id = ? "
                + "ORDER BY asset_class, created_at, id", (rs, n) -> map(rs), userId));
    }

    /** One ClassTotal (count 1) per holding; grouping and summing is done by the rules package. */
    public List<ClassTotal> amounts(UUID userId) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("SELECT asset_class, invested_paise, current_value_paise, "
                + "monthly_contribution_paise FROM holding WHERE user_id = ?", (rs, n) -> new ClassTotal(
                        rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), 1), userId));
    }

    /** True if a holding was removed. */
    public boolean delete(UUID userId, UUID id) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.update("DELETE FROM holding WHERE id = ? AND user_id = ?", id, userId) > 0);
    }

    private static Row map(ResultSet rs) throws SQLException {
        return new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5),
                rs.getLong(6), rs.getTimestamp(7).toInstant());
    }
}
