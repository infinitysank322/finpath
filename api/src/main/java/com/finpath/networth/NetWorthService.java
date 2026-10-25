package com.finpath.networth;

import com.finpath.dpdp.ConsentService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads the stored net-worth snapshots, oldest first. Does no arithmetic and logs nothing. */
@Service
public class NetWorthService {

    /** Most recent snapshots returned; the series is a chart input, not an archive. */
    static final int MAX_POINTS = 120;

    public record PointView(LocalDate cycleStart, long assetsPaise, long liabilitiesPaise, long netWorthPaise) {
    }

    public record SeriesView(List<PointView> points) {
    }

    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;
    private final ConsentService consents;

    @Autowired
    public NetWorthService(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx,
            ConsentService consents) {
        this(() -> required(jdbc.getIfAvailable()), () -> required(tx.getIfAvailable()), consents);
    }

    public NetWorthService(Supplier<JdbcTemplate> jdbc, Supplier<TransactionTemplate> tx, ConsentService consents) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.consents = consents;
    }

    private static <T> T required(T bean) {
        if (bean == null) {
            throw new IllegalStateException("No datasource configured");
        }
        return bean;
    }

    /** Net worth now, from the same inputs and rule as the cycle-close snapshot. */
    public NetWorthSnapshots.Position current(UUID userId) {
        consents.require(userId);
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> NetWorthSnapshots.position(j, userId));
    }

    public SeriesView series(UUID userId) {
        consents.require(userId);
        JdbcTemplate j = jdbc.get();
        List<PointView> newestFirst = tx.get().execute(status -> j.query(
                "SELECT cycle_start, assets_paise, liabilities_paise, net_worth_paise FROM net_worth_snapshot "
                        + "WHERE user_id = ? ORDER BY cycle_start DESC LIMIT ?",
                (rs, n) -> new PointView(rs.getObject(1, LocalDate.class), rs.getLong(2), rs.getLong(3),
                        rs.getLong(4)), userId, MAX_POINTS));
        return new SeriesView(newestFirst.reversed());
    }
}
