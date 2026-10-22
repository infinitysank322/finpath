package com.finpath.rebalance;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Read-only access to the user's OPEN plan_cycle row, inside the RLS-bound transaction. */
@Repository
public class RebalanceRepository {

    /** Stored plan: surplus is null until computed; allocations is the raw jsonb text. */
    public record Plan(Long surplusPaise, String allocationsJson) {
    }

    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;

    @Autowired
    public RebalanceRepository(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx) {
        this(() -> required(jdbc.getIfAvailable()), () -> required(tx.getIfAvailable()));
    }

    public RebalanceRepository(Supplier<JdbcTemplate> jdbc, Supplier<TransactionTemplate> tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    private static <T> T required(T bean) {
        if (bean == null) {
            throw new IllegalStateException("No datasource configured");
        }
        return bean;
    }

    public Optional<Plan> openPlan(UUID userId, LocalDate cycleStart) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> j.query("SELECT surplus, allocations::text FROM plan_cycle "
                + "WHERE user_id = ? AND cycle_start = ? AND status = 'OPEN'", (rs, n) -> {
                    long surplus = rs.getLong(1);
                    return new Plan(rs.wasNull() ? null : surplus, rs.getString(2));
                }, userId, cycleStart).stream().findFirst());
    }
}
