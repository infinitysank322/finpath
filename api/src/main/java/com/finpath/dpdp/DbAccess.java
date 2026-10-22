package com.finpath.dpdp;

import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lazy handle on the datasource beans: profiles without a database (ci, unit tests) still start,
 * and only a call that needs the database fails.
 */
final class DbAccess {

    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;

    DbAccess(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx) {
        this.jdbc = () -> required(jdbc.getIfAvailable());
        this.tx = () -> required(tx.getIfAvailable());
    }

    DbAccess(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = () -> jdbc;
        this.tx = () -> tx;
    }

    JdbcTemplate jdbc() {
        return jdbc.get();
    }

    TransactionTemplate tx() {
        return tx.get();
    }

    private static <T> T required(T bean) {
        if (bean == null) {
            throw new IllegalStateException("No datasource configured");
        }
        return bean;
    }
}
