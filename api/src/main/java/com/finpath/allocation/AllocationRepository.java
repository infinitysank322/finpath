package com.finpath.allocation;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads and writes surplus and allocations on the caller's OPEN plan_cycle row, inside the RLS-bound transaction. */
@Repository
public class AllocationRepository {

    /** Stored plan: surplus is null until a run; allocations is the raw jsonb text. */
    public record Stored(LocalDate cycleStart, Long surplusPaise, String allocationsJson) {
    }

    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;

    @Autowired
    public AllocationRepository(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx) {
        this(() -> required(jdbc.getIfAvailable()), () -> required(tx.getIfAvailable()));
    }

    public AllocationRepository(Supplier<JdbcTemplate> jdbc, Supplier<TransactionTemplate> tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private static <T> T required(T bean) {
        if (bean == null) {
            throw new IllegalStateException("No datasource configured");
        }
        return bean;
    }

    /** Overwrites the open cycle's result; false when there is no such open row. */
    public boolean save(UUID userId, LocalDate cycleStart, long surplusPaise, String allocationsJson) {
        JdbcTemplate j = jdbc.get();
        Integer n = tx.get().execute(status -> j.update("UPDATE plan_cycle SET surplus = ?, allocations = ?::jsonb "
                + "WHERE user_id = ? AND cycle_start = ? AND status = 'OPEN'", surplusPaise, allocationsJson, userId,
                cycleStart));
        return n != null && n > 0;
    }

    public Optional<Stored> open(UUID userId) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("SELECT cycle_start, surplus, allocations::text FROM plan_cycle "
                + "WHERE user_id = ? AND status = 'OPEN'", (rs, n) -> {
                    long surplus = rs.getLong(2);
                    return new Stored(rs.getObject(1, LocalDate.class), rs.wasNull() ? null : surplus,
                            rs.getString(3));
                }, userId).stream().findFirst());
    }
}
