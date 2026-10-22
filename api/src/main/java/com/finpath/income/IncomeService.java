package com.finpath.income;

import com.finpath.dpdp.ConsentService;
import com.finpath.rules.CycleCalendar;
import com.finpath.rules.IncomeCalculator;
import com.finpath.rules.IncomeCalculator.Frequency;
import com.finpath.rules.IncomeCalculator.Source;
import com.finpath.rules.IncomeCalculator.Type;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores income sources and reports the current cycle's income. It only fetches the user's rows
 * (RLS-filtered) and the cycle bounds; inclusion and totals come from {@link IncomeCalculator}.
 * Nothing here logs row content.
 */
@Service
public class IncomeService {

    /** The product serves India, so "today" is the Indian calendar date. */
    static final ZoneId CYCLE_ZONE = ZoneId.of("Asia/Kolkata");

    /** The write payload. Every field is validated here; the owner is never part of it. */
    public record IncomeInput(Type type, Frequency frequency, Long amountPaise, LocalDate startDate,
            LocalDate endDate) {
    }

    public record IncomeView(UUID id, Type type, Frequency frequency, long amountPaise, LocalDate startDate,
            LocalDate endDate, boolean countsInCurrentCycle) {
    }

    public record CycleItem(UUID id, Type type, long amountPaise) {
    }

    public record CycleTotalView(LocalDate cycleStart, LocalDate cycleEndExclusive, long totalPaise,
            List<CycleItem> items) {
    }

    private record Row(UUID id, Source source) {
    }

    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;
    private final ConsentService consents;
    private final Clock clock;

    @Autowired
    public IncomeService(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx,
            ConsentService consents) {
        this(() -> required(jdbc.getIfAvailable()), () -> required(tx.getIfAvailable()), consents,
                Clock.system(CYCLE_ZONE));
    }

    IncomeService(Supplier<JdbcTemplate> jdbc, Supplier<TransactionTemplate> tx, ConsentService consents,
            Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.consents = consents;
        this.clock = clock;
    }

    private static <T> T required(T bean) {
        if (bean == null) {
            throw new IllegalStateException("No datasource configured");
        }
        return bean;
    }

    public IncomeView create(UUID userId, IncomeInput in) {
        Source source = validate(in);
        consents.require(userId);
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> {
            UUID id = j.queryForObject("INSERT INTO income_source (user_id, type, frequency, amount_paise, "
                    + "start_date, end_date) VALUES (?, ?, ?, ?, ?, ?) RETURNING id", UUID.class, userId,
                    source.type().name(), source.frequency().name(), source.amountPaise(), source.startDate(),
                    source.endDate());
            return view(new Row(id, source), cycle(j, userId));
        });
    }

    /** All the user's sources, newest start date first. */
    public List<IncomeView> list(UUID userId) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> {
            CycleCalendar.Cycle cycle = cycle(j, userId);
            return rows(j, userId).stream().map(r -> view(r, cycle)).toList();
        });
    }

    public IncomeView replace(UUID userId, UUID id, IncomeInput in) {
        Source source = validate(in);
        consents.require(userId);
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> {
            int n = j.update("UPDATE income_source SET type = ?, frequency = ?, amount_paise = ?, start_date = ?, "
                    + "end_date = ? WHERE id = ? AND user_id = ?", source.type().name(), source.frequency().name(),
                    source.amountPaise(), source.startDate(), source.endDate(), id, userId);
            if (n == 0) {
                throw new IncomeNotFoundException();
            }
            return view(new Row(id, source), cycle(j, userId));
        });
    }

    public void delete(UUID userId, UUID id) {
        consents.require(userId);
        JdbcTemplate j = jdbc.get();
        tx.get().executeWithoutResult(status -> {
            if (j.update("DELETE FROM income_source WHERE id = ? AND user_id = ?", id, userId) == 0) {
                throw new IncomeNotFoundException();
            }
        });
    }

    /** Income for the current cycle; the total and the item list come from the rules package. */
    public CycleTotalView cycleTotal(UUID userId) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> {
            CycleCalendar.Cycle cycle = cycle(j, userId);
            List<Row> rows = rows(j, userId);
            long total = IncomeCalculator.cycleTotal(rows.stream().map(Row::source).toList(), cycle);
            List<CycleItem> items = rows.stream().filter(r -> IncomeCalculator.countsIn(r.source(), cycle))
                    .map(r -> new CycleItem(r.id(), r.source().type(), r.source().amountPaise())).toList();
            return new CycleTotalView(cycle.start(), cycle.endExclusive(), total, items);
        });
    }

    private static Source validate(IncomeInput in) {
        if (in == null || in.type() == null || in.frequency() == null || in.amountPaise() == null
                || in.amountPaise() <= 0 || in.startDate() == null) {
            throw new InvalidIncomeException();
        }
        try {
            return new Source(in.type(), in.amountPaise(), in.frequency(), in.startDate(), in.endDate());
        } catch (IllegalArgumentException e) {
            throw new InvalidIncomeException();
        }
    }

    private CycleCalendar.Cycle cycle(JdbcTemplate j, UUID userId) {
        List<Integer> days = j.query("SELECT cycle_start_day FROM users WHERE id = ?", (rs, n) -> rs.getInt(1),
                userId);
        if (days.isEmpty()) {
            throw new IncomeNotFoundException();
        }
        return CycleCalendar.cycleFor(LocalDate.now(clock), days.get(0));
    }

    private static List<Row> rows(JdbcTemplate j, UUID userId) {
        return j.query("SELECT id, type, frequency, amount_paise, start_date, end_date FROM income_source "
                + "WHERE user_id = ? ORDER BY start_date DESC, created_at DESC, id", (rs, n) -> {
                    LocalDate end = rs.getObject(6, LocalDate.class);
                    return new Row(rs.getObject(1, UUID.class), new Source(Type.valueOf(rs.getString(2)),
                            rs.getLong(4), Frequency.valueOf(rs.getString(3)), rs.getObject(5, LocalDate.class),
                            end));
                }, userId);
    }

    private static IncomeView view(Row r, CycleCalendar.Cycle cycle) {
        Source s = r.source();
        return new IncomeView(r.id(), s.type(), s.frequency(), s.amountPaise(), s.startDate(), s.endDate(),
                IncomeCalculator.countsIn(s, cycle));
    }
}
