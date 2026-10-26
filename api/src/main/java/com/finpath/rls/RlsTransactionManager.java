package com.finpath.rls;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The connection initialiser for row-level security (T30). At the start of every real database
 * transaction it runs, on that connection:
 *
 * <ol>
 *   <li>{@code SET LOCAL ROLE app_user}: the transaction runs as the RLS-bound role even though the
 *       login role may own the tables (migrations) or be a superuser (local dev);
 *   <li>{@code set_config('app.current_user_id', <id>, true)}: the caller's id from the verified
 *       access token ({@link CurrentUser}), which every {@code *_self} policy keys on.
 * </ol>
 *
 * Both are transaction-local, so they vanish at commit or rollback and can never leak to the next
 * borrower of a pooled connection (this also works behind a transaction-mode pooler). With no
 * authenticated user (sign-in) the id is left unset and the policies match no row: the table fails
 * closed. Statements issued outside a transaction get neither, which is why services must be
 * transactional; {@link RlsRoleGuard} refuses a misconfigured app_user and warns when the login role itself
 * bypasses RLS (superuser, BYPASSRLS), since such statements would then see every tenant's rows.
 */
public class RlsTransactionManager extends DataSourceTransactionManager {

    static final String APP_ROLE = "app_user";

    private final Supplier<Optional<UUID>> currentUser;

    public RlsTransactionManager(DataSource dataSource) {
        this(dataSource, RlsScope::principalOrScoped);
    }

    public RlsTransactionManager(DataSource dataSource, Supplier<Optional<UUID>> currentUser) {
        super(dataSource);
        this.currentUser = currentUser;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager.getResource(obtainDataSource());
        try {
            bind(holder.getConnection(), currentUser.get());
        } catch (SQLException e) {
            // doBegin failed after the connection was taken: let the manager release it.
            doCleanupAfterCompletion(transaction);
            throw new CannotCreateTransactionException("Could not bind the row-level-security context", e);
        }
    }

    /** Must run inside the open transaction; both settings are LOCAL to it. */
    static void bind(Connection c, Optional<UUID> user) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("SET LOCAL ROLE " + APP_ROLE);
        }
        // The consent statement version the code requires; finpath_has_consent() compares against it,
        // so consent given to an older wording stops counting without a migration.
        try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.consent_version', ?, true)")) {
            ps.setString(1, com.finpath.dpdp.ConsentStatement.CURRENT.version());
            ps.execute();
        }
        if (user.isPresent()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.current_user_id', ?, true)")) {
                ps.setString(1, user.get().toString());
                ps.execute();
            }
        }
    }
}
