package com.finpath.networth;

import com.finpath.rules.Money;
import com.finpath.rules.Statistics;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Writes the FR-26 net-worth snapshot of a cycle at the moment it closes. Called only from the cycle-close
 * step, inside its RLS transaction; no endpoint writes snapshots. The figure comes from
 * {@link Statistics#netWorth}; SQL only sums the stored rows. Idempotent per cycle. Logs nothing.
 */
public final class NetWorthSnapshots {

    private NetWorthSnapshots() {
    }

    /** Assets, liabilities and their difference, all from the stored rows. */
    public record Position(long assetsPaise, long liabilitiesPaise, long netWorthPaise) {
    }

    /** The position right now; the same inputs and rule the close-time snapshot uses. */
    public static Position position(JdbcTemplate j, UUID userId) {
        long assets = sum(j, "SELECT COALESCE(SUM(current_value_paise), 0) FROM holding WHERE user_id = ?", userId);
        long liabilities = sum(j,
                "SELECT COALESCE(SUM(principal_outstanding_paise), 0) FROM loan WHERE user_id = ?", userId);
        long net = Statistics.netWorth(Money.ofPaise(assets), Money.ofPaise(liabilities)).paise();
        return new Position(assets, liabilities, net);
    }

    /** Inserts the snapshot for {@code planCycleId} unless one exists. Returns true when a row was written. */
    public static boolean recordAtClose(JdbcTemplate j, UUID userId, UUID planCycleId, LocalDate cycleStart) {
        Position p = position(j, userId);
        return j.update("INSERT INTO net_worth_snapshot (user_id, plan_cycle_id, cycle_start, assets_paise, "
                + "liabilities_paise, net_worth_paise) VALUES (?, ?, ?, ?, ?, ?) "
                + "ON CONFLICT (plan_cycle_id) DO NOTHING", userId, planCycleId, cycleStart, p.assetsPaise(),
                p.liabilitiesPaise(), p.netWorthPaise()) == 1;
    }

    private static long sum(JdbcTemplate j, String sql, UUID userId) {
        Long v = j.queryForObject(sql, Long.class, userId);
        return v == null ? 0L : v;
    }
}
