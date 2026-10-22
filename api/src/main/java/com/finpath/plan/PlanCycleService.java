package com.finpath.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finpath.dpdp.ConsentService;
import com.finpath.rules.CycleCalendar;
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
 * Materialises the user's current budget cycle as a plan_cycle row. All cycle boundaries and day
 * counts come from {@link CycleCalendar}; this class only stores them. Everything runs in one RLS
 * transaction and starts by locking the user's row, so concurrent calls for one user serialise.
 * Nothing here logs row content.
 */
@Service
public class PlanCycleService {

    static final String OPEN = "OPEN";

    /** The cycle zone: the product serves India, so "today" is the Indian calendar date. */
    static final ZoneId CYCLE_ZONE = ZoneId.of("Asia/Kolkata");

    /**
     * @param cycleEndExclusive first day of the next cycle
     * @param surplusPaise null until the allocation run has computed it
     * @param allocations waterfall lines (paise), an empty array until computed
     */
    public record PlanCycleView(LocalDate cycleStart, LocalDate cycleEndExclusive, int startDay, String status,
            Long surplusPaise, JsonNode allocations, int daysElapsed, int daysLeft) {
    }

    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;
    private final ConsentService consents;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public PlanCycleService(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx,
            ConsentService consents, ObjectMapper mapper) {
        this(() -> required(jdbc.getIfAvailable()), () -> required(tx.getIfAvailable()), consents, mapper,
                Clock.system(CYCLE_ZONE));
    }

    PlanCycleService(Supplier<JdbcTemplate> jdbc, Supplier<TransactionTemplate> tx, ConsentService consents,
            ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.consents = consents;
        this.mapper = mapper;
        this.clock = clock;
    }

    private static <T> T required(T bean) {
        if (bean == null) {
            throw new IllegalStateException("No datasource configured");
        }
        return bean;
    }

    /** The current cycle, created if absent and with any elapsed open cycle closed. Idempotent. */
    public PlanCycleView current(UUID userId) {
        consents.require(userId);
        JdbcTemplate j = jdbc.get();
        LocalDate today = LocalDate.now(clock);
        return tx.get().execute(status -> derive(j, userId, today, lockStartDay(j, userId)));
    }

    /**
     * Sets users.cycle_start_day and re-derives the current cycle in the same transaction. The
     * previously open cycle is marked SUPERSEDED (or CLOSED if it had already ended), never deleted.
     *
     * @throws InvalidStartDayException when {@code startDay} is null or outside 1..31
     */
    public PlanCycleView changeStartDay(UUID userId, Integer startDay) {
        if (startDay == null || startDay < CycleCalendar.MIN_START_DAY || startDay > CycleCalendar.MAX_START_DAY) {
            throw new InvalidStartDayException();
        }
        consents.require(userId);
        JdbcTemplate j = jdbc.get();
        LocalDate today = LocalDate.now(clock);
        return tx.get().execute(status -> {
            lockStartDay(j, userId);
            j.update("UPDATE users SET cycle_start_day = ? WHERE id = ?", startDay, userId);
            return derive(j, userId, today, startDay);
        });
    }

    private static int lockStartDay(JdbcTemplate j, UUID userId) {
        List<Integer> days = j.query("SELECT cycle_start_day FROM users WHERE id = ? FOR UPDATE",
                (rs, n) -> rs.getInt(1), userId);
        if (days.isEmpty()) {
            throw new PlanUserNotFoundException();
        }
        return days.get(0);
    }

    private PlanCycleView derive(JdbcTemplate j, UUID userId, LocalDate today, int startDay) {
        CycleCalendar.Cycle cycle = CycleCalendar.cycleFor(today, startDay);
        LocalDate start = cycle.start();
        LocalDate end = cycle.endExclusive();

        // Retire any other open cycle first (the partial unique index allows one OPEN row per user).
        j.update("UPDATE plan_cycle SET status = CASE WHEN cycle_end <= ? THEN 'CLOSED' ELSE 'SUPERSEDED' END "
                + "WHERE user_id = ? AND status = 'OPEN' AND cycle_start <> ?", today, userId, start);

        // Create, or revive a row for the same start date. If the end moved (day 30 vs 31 in February),
        // the stored surplus and allocations were computed for other bounds, so they are cleared.
        j.update("INSERT INTO plan_cycle (user_id, cycle_start, cycle_end, start_day, status) "
                + "VALUES (?, ?, ?, ?, 'OPEN') "
                + "ON CONFLICT (user_id, cycle_start) DO UPDATE SET status = 'OPEN', "
                + "start_day = EXCLUDED.start_day, cycle_end = EXCLUDED.cycle_end, "
                + "surplus = CASE WHEN plan_cycle.cycle_end = EXCLUDED.cycle_end THEN plan_cycle.surplus END, "
                + "allocations = CASE WHEN plan_cycle.cycle_end = EXCLUDED.cycle_end "
                + "THEN plan_cycle.allocations ELSE '[]'::jsonb END "
                + "WHERE plan_cycle.status <> 'OPEN' OR plan_cycle.cycle_end <> EXCLUDED.cycle_end "
                + "OR plan_cycle.start_day <> EXCLUDED.start_day",
                userId, start, end, startDay);

        return j.queryForObject("SELECT status, surplus, allocations::text FROM plan_cycle "
                + "WHERE user_id = ? AND cycle_start = ?", (rs, n) -> {
                    long surplus = rs.getLong(2);
                    Long surplusPaise = rs.wasNull() ? null : surplus;
                    return new PlanCycleView(start, end, startDay, rs.getString(1), surplusPaise,
                            parse(rs.getString(3)), CycleCalendar.daysElapsed(cycle, today),
                            CycleCalendar.daysLeft(cycle, today));
                }, userId, start);
    }

    private JsonNode parse(String json) {
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("stored allocations are not valid JSON");
        }
    }
}
