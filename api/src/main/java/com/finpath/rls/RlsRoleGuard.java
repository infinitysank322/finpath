package com.finpath.rls;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup check that the role every transaction runs as is really bound by row-level security:
 * {@code app_user} must exist, be assumable by the login role, and be neither a superuser nor
 * BYPASSRLS (either would skip every policy), and must own no table (an owner is only bound
 * because of FORCE). Failing here is deliberate: a misconfigured role is a silent data leak.
 */
public final class RlsRoleGuard {

    private static final Logger log = LoggerFactory.getLogger(RlsRoleGuard.class);

    private RlsRoleGuard() {
    }

    /** The login role itself may bypass RLS (superuser in local dev); only transactions are bound. */
    private static void warnIfLoginRoleBypassesRls(Statement s) throws SQLException {
        try (ResultSet rs = s.executeQuery("SELECT r.rolsuper OR r.rolbypassrls FROM pg_catalog.pg_roles r "
                + "WHERE r.rolname = session_user")) {
            if (rs.next() && rs.getBoolean(1)) {
                log.warn("Row-level security: the login role bypasses RLS. Transactions are bound via SET LOCAL ROLE "
                        + "app_user, but any statement run outside a transaction is NOT; keep all data access transactional.");
            }
        }
    }

    public static void verify(DataSource dataSource) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                warnIfLoginRoleBypassesRls(s);
                s.execute("SET LOCAL ROLE " + RlsTransactionManager.APP_ROLE);
                try (ResultSet rs = s.executeQuery("SELECT current_user, r.rolsuper, r.rolbypassrls, "
                        + "EXISTS (SELECT 1 FROM pg_catalog.pg_class t WHERE t.relowner = r.oid AND t.relkind IN ('r','p')) "
                        + "FROM pg_catalog.pg_roles r WHERE r.rolname = current_user")) {
                    if (!rs.next() || !RlsTransactionManager.APP_ROLE.equals(rs.getString(1))) {
                        throw new IllegalStateException("Row-level security: could not assume role app_user");
                    }
                    if (rs.getBoolean(2) || rs.getBoolean(3) || rs.getBoolean(4)) {
                        throw new IllegalStateException(
                                "Row-level security: role app_user must not be a superuser, BYPASSRLS or a table owner");
                    }
                }
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "Row-level security: the database login must be able to SET ROLE app_user (run migrations first, "
                            + "and GRANT app_user to the login role if it is not a superuser)", e);
        }
    }
}
