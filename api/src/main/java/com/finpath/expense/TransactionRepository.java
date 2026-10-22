package com.finpath.expense;

import com.finpath.rules.TransactionHash;
import com.finpath.rules.TransactionHash.Direction;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persistence for the transaction table. Runs inside the RLS-bound transaction wrapper, so the
 * database confines every call to the caller's rows; the owner id passed in must come from the
 * authenticated principal. The dedupe hash is always computed here, never caller-supplied.
 * Nothing here logs row content (the note is free text).
 */
@Repository
public class TransactionRepository {

    /** A write payload. {@code source} defaults to MANUAL; only the recurring materialiser sets RECURRING. */
    public record Draft(LocalDate txnDate, long amountPaise, Direction direction, String categoryCode, String mode,
            String tag, String note, String idempotencyKey, String source) {

        public Draft(LocalDate txnDate, long amountPaise, Direction direction, String categoryCode, String mode,
                String tag, String note, String idempotencyKey) {
            this(txnDate, amountPaise, direction, categoryCode, mode, tag, note, idempotencyKey, "MANUAL");
        }
    }

    public record Transaction(UUID id, LocalDate txnDate, long amountPaise, Direction direction,
            String categoryCode, String mode, String tag, String source, String note, String dedupeHash,
            String idempotencyKey) {
    }

    /** {@code created} is false when the idempotency key already existed and {@code row} is the original. */
    public record InsertResult(Transaction row, boolean created) {
    }

    private static final String SELECT = "SELECT t.id, t.txn_date, t.amount_paise, t.direction, c.code, t.mode, "
            + "t.tag, t.source, t.note, t.dedupe_hash, t.idempotency_key FROM transaction t "
            + "JOIN category c ON c.id = t.category_id ";

    private static final RowMapper<Transaction> MAP = (rs, i) -> new Transaction(rs.getObject(1, UUID.class),
            rs.getObject(2, LocalDate.class), rs.getLong(3), Direction.valueOf(rs.getString(4)), rs.getString(5),
            rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11));

    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;

    @Autowired
    public TransactionRepository(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx) {
        this(() -> required(jdbc.getIfAvailable()), () -> required(tx.getIfAvailable()));
    }

    public TransactionRepository(Supplier<JdbcTemplate> jdbc, Supplier<TransactionTemplate> tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private static <T> T required(T bean) {
        if (bean == null) {
            throw new IllegalStateException("No datasource configured");
        }
        return bean;
    }

    /** Inserts, or returns the existing row when (user, idempotencyKey) was already stored. */
    public InsertResult insert(UUID userId, Draft d) {
        String hash = hash(d);
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> {
            UUID categoryId = categoryId(j, d.categoryCode());
            List<UUID> ids = j.queryForList("INSERT INTO transaction (user_id, txn_date, amount_paise, direction, "
                    + "category_id, mode, tag, source, note, dedupe_hash, idempotency_key) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT (user_id, idempotency_key) DO NOTHING RETURNING id", UUID.class, userId,
                    d.txnDate(), d.amountPaise(), d.direction().name(), categoryId, d.mode().trim().toUpperCase(
                            java.util.Locale.ROOT), d.tag(), d.source(), d.note(), hash, d.idempotencyKey());
            if (!ids.isEmpty()) {
                return new InsertResult(j.queryForObject(SELECT + "WHERE t.id = ?", MAP, ids.get(0)), true);
            }
            return new InsertResult(j.queryForObject(SELECT + "WHERE t.user_id = ? AND t.idempotency_key = ?", MAP,
                    userId, d.idempotencyKey()), false);
        });
    }

    public Optional<Transaction> findByIdempotencyKey(UUID userId, String key) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query(SELECT + "WHERE t.user_id = ? AND t.idempotency_key = ?", MAP,
                userId, key).stream().findFirst());
    }

    public Optional<Transaction> findById(UUID userId, UUID id) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query(SELECT + "WHERE t.user_id = ? AND t.id = ?", MAP, userId, id)
                .stream().findFirst());
    }

    /** Rows with {@code from <= txn_date < toExclusive}, newest first. */
    public List<Transaction> listBetween(UUID userId, LocalDate from, LocalDate toExclusive) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query(SELECT + "WHERE t.user_id = ? AND t.txn_date >= ? "
                + "AND t.txn_date < ? ORDER BY t.txn_date DESC, t.created_at DESC, t.id", MAP, userId, from,
                toExclusive));
    }

    /** Full replace of the editable fields; recomputes the hash. The idempotency key and source are kept. */
    public Optional<Transaction> update(UUID userId, UUID id, Draft d) {
        String hash = hash(d);
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> {
            UUID categoryId = categoryId(j, d.categoryCode());
            int n = j.update("UPDATE transaction SET txn_date = ?, amount_paise = ?, direction = ?, category_id = ?, "
                    + "mode = ?, tag = ?, note = ?, dedupe_hash = ? WHERE id = ? AND user_id = ?", d.txnDate(),
                    d.amountPaise(), d.direction().name(), categoryId,
                    d.mode().trim().toUpperCase(java.util.Locale.ROOT), d.tag(), d.note(), hash, id, userId);
            return n == 0 ? Optional.<Transaction>empty()
                    : j.query(SELECT + "WHERE t.id = ?", MAP, id).stream().findFirst();
        });
    }

    /** Sets or clears the tag of an OUT row only; the dedupe hash does not cover the tag. */
    public Optional<Transaction> updateTag(UUID userId, UUID id, String tag) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> {
            int n = j.update("UPDATE transaction SET tag = ? WHERE id = ? AND user_id = ? AND direction = 'OUT'",
                    tag, id, userId);
            return n == 0 ? Optional.<Transaction>empty()
                    : j.query(SELECT + "WHERE t.id = ?", MAP, id).stream().findFirst();
        });
    }

    public boolean delete(UUID userId, UUID id) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.update("DELETE FROM transaction WHERE id = ? AND user_id = ?", id,
                userId) > 0);
    }

    /** The subset of {@code hashes} that more than one of the user's rows carries (a duplicate hint only). */
    public java.util.Set<String> repeatedHashes(UUID userId, java.util.Collection<String> hashes) {
        if (hashes.isEmpty()) {
            return java.util.Set.of();
        }
        JdbcTemplate j = jdbc.get();
        String marks = String.join(",", java.util.Collections.nCopies(hashes.size(), "?"));
        Object[] args = new Object[hashes.size() + 1];
        args[0] = userId;
        int i = 1;
        for (String h : hashes) {
            args[i++] = h;
        }
        return tx.get().execute(status -> new java.util.HashSet<>(j.queryForList("SELECT dedupe_hash FROM "
                + "transaction WHERE user_id = ? AND dedupe_hash IN (" + marks + ") GROUP BY dedupe_hash "
                + "HAVING count(*) > 1", String.class, args)));
    }

    private static String hash(Draft d) {
        return TransactionHash.dedupeHash(d.txnDate(), d.amountPaise(), d.direction(), d.categoryCode(), d.mode());
    }

    private static UUID categoryId(JdbcTemplate j, String code) {
        List<UUID> ids = j.queryForList("SELECT id FROM category WHERE code = ?", UUID.class,
                code.trim().toUpperCase(java.util.Locale.ROOT));
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("unknown category code");
        }
        return ids.get(0);
    }
}
