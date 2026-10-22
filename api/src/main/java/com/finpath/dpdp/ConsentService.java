package com.finpath.dpdp;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Consent capture (DPDP). The authoritative gate is in the database: business-table policies call
 * {@code finpath_has_consent()} in WITH CHECK (see V3). {@link #require} is the service-layer
 * early check that turns the same condition into a clean 403 instead of an RLS violation; every
 * service that writes a business table calls it first.
 */
@Service
public class ConsentService {

    public record Consent(String purpose, String statementVersion, Instant grantedAt, Instant withdrawnAt) {
    }

    private final DbAccess db;

    @Autowired
    public ConsentService(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx) {
        this(new DbAccess(jdbc, tx));
    }

    ConsentService(DbAccess db) {
        this.db = db;
    }

    /**
     * Records consent to the current statement; the caller names the version it displayed and a
     * stale or unknown one is refused. Idempotent: an existing live grant is returned unchanged.
     */
    public Consent grant(UUID userId, String purpose, String statementVersion) {
        ConsentStatement s = ConsentStatement.CURRENT;
        if (!s.purpose().equals(purpose) || !s.version().equals(statementVersion)) {
            throw new ConsentStatementMismatchException();
        }
        JdbcTemplate jdbc = db.jdbc();
        return db.tx().execute(status -> {
            // A live grant of an older wording does not count; retire it so the new grant can be recorded.
            jdbc.update("UPDATE consent SET withdrawn_at = now() WHERE user_id = ? AND purpose = ? "
                    + "AND withdrawn_at IS NULL AND statement_version <> ?", userId, s.purpose(), s.version());
            jdbc.update("INSERT INTO consent (user_id, purpose, statement_version, purpose_statement) "
                    + "VALUES (?, ?, ?, ?) ON CONFLICT (user_id, purpose) WHERE withdrawn_at IS NULL DO NOTHING",
                    userId, s.purpose(), s.version(), s.text());
            return active(jdbc, userId).orElseThrow(() -> new IllegalStateException("consent row not found"));
        });
    }

    /** Ends the live consent. Returns false if there was none. The account is purged after the grace period. */
    public boolean withdraw(UUID userId) {
        JdbcTemplate jdbc = db.jdbc();
        Integer n = db.tx().execute(status -> jdbc.update(
                "UPDATE consent SET withdrawn_at = now() WHERE user_id = ? AND withdrawn_at IS NULL", userId));
        return n != null && n > 0;
    }

    /** The live consent to the CURRENT statement version, if any; consent to an older wording is not returned. */
    public Optional<Consent> current(UUID userId) {
        JdbcTemplate jdbc = db.jdbc();
        return db.tx().execute(status -> active(jdbc, userId));
    }

    /** @throws ConsentRequiredException unless a live consent to the current statement version exists */
    public void require(UUID userId) {
        if (current(userId).isEmpty()) {
            throw new ConsentRequiredException();
        }
    }

    private static Optional<Consent> active(JdbcTemplate jdbc, UUID userId) {
        List<Consent> rows = jdbc.query(
                "SELECT purpose, statement_version, granted_at, withdrawn_at FROM consent "
                        + "WHERE user_id = ? AND purpose = ? AND statement_version = ? AND withdrawn_at IS NULL",
                (rs, n) -> new Consent(rs.getString(1), rs.getString(2),
                        rs.getObject(3, java.time.OffsetDateTime.class).toInstant(), null),
                userId, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
        return rows.stream().findFirst();
    }
}
