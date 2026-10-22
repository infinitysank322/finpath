package com.finpath.user;

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
 * JDBC implementation over V2 {@code users}. Matches on the verified, lower-cased email (UNIQUE in
 * V2); no Google subject or token is stored.
 *
 * <p>Works under the V2 row-level-security policy (role {@code app_user}): the lookup goes through
 * the V2.1 SECURITY DEFINER function {@code finpath_user_id_by_email}, which returns only an id,
 * and the insert sets {@code app.current_user_id} to the new row's id for this transaction only,
 * so it passes the {@code users_self} WITH CHECK and its RETURNING passes USING.
 *
 * <p>The datasource is resolved lazily: profiles without a database (ci, unit tests) still start.
 */
@Repository
public class JdbcUserAccounts implements UserAccounts {

    private static final int MAX_NAME_LENGTH = 200;

    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;

    @Autowired
    public JdbcUserAccounts(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx) {
        this.jdbc = () -> required(jdbc.getIfAvailable());
        this.tx = () -> required(tx.getIfAvailable());
    }

    JdbcUserAccounts(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = () -> jdbc;
        this.tx = () -> tx;
    }

    private static <T> T required(T bean) {
        if (bean == null) {
            throw new IllegalStateException("No datasource configured: sign-in needs the users table");
        }
        return bean;
    }

    @Override
    public UUID findOrCreateByVerifiedEmail(String email, String displayName) {
        JdbcTemplate db = jdbc.get();
        return tx.get().execute(status -> {
            Optional<UUID> existing = findIdByEmail(db, email);
            if (existing.isPresent()) {
                return existing.get();
            }
            UUID id = UUID.randomUUID();
            db.queryForObject("SELECT set_config('app.current_user_id', ?, true)", String.class, id.toString());
            List<UUID> inserted = db.query(
                    "INSERT INTO users (id, name, email) VALUES (?, ?, ?) ON CONFLICT (email) DO NOTHING RETURNING id",
                    (rs, n) -> rs.getObject(1, UUID.class), id, nameOrDefault(displayName, email), email);
            if (!inserted.isEmpty()) {
                return inserted.get(0);
            }
            // ON CONFLICT skipped the insert: a concurrent first sign-in with the same email committed
            // between our lookup and our insert. That row is committed, so a fresh lookup finds it.
            return findIdByEmail(db, email).orElseThrow(
                    () -> new IllegalStateException("user row neither inserted nor found"));
        });
    }

    /** Through the V2.1 function, never a direct SELECT: RLS hides every row whose id is not yet known. */
    private static Optional<UUID> findIdByEmail(JdbcTemplate db, String email) {
        return Optional.ofNullable(
                db.queryForObject("SELECT finpath_user_id_by_email(?)", (rs, n) -> rs.getObject(1, UUID.class), email));
    }

    static String nameOrDefault(String displayName, String email) {
        String name = displayName == null ? "" : displayName.strip();
        if (name.isEmpty()) {
            name = email.substring(0, Math.max(0, email.indexOf('@')));
        }
        if (name.isEmpty()) {
            name = "FinPath user";
        }
        return name.codePointCount(0, name.length()) > MAX_NAME_LENGTH
                ? name.substring(0, name.offsetByCodePoints(0, MAX_NAME_LENGTH))
                : name;
    }
}
