package com.finpath.checklist;

import com.finpath.dpdp.ConsentService;
import com.finpath.plan.PlanCycleService;
import com.finpath.rules.Checklist;
import com.finpath.rules.Statistics;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * FR-26 monthly checklist. Items are generated from stored loans and the stored allocation lines (read,
 * never recomputed), amounts frozen at generation. This class does no arithmetic: counts come from SQL
 * and the completion and streak decisions come from the rules package. Logs nothing.
 */
@Service
public class ChecklistService {

    /** Cycles returned in the streak history (the heatmap window). */
    static final int HISTORY_CYCLES = 12;

    public record ItemView(UUID id, String type, String sourceRef, String label, long amountPaise, boolean done,
            Instant doneAt) {
    }

    public record ListView(LocalDate cycleStart, List<ItemView> items, int doneCount, int totalCount) {
    }

    public record CycleFlag(LocalDate cycleStart, boolean done) {
    }

    public record StreakView(int streak, List<CycleFlag> history) {
    }

    private record CycleRow(LocalDate cycleStart, String status) {
    }

    private static final String ITEM_SELECT = "SELECT i.id, i.type, i.source_ref, "
            + "CASE i.type WHEN 'EMI' THEN COALESCE(l.lender, 'EMI') WHEN 'GOAL_TRANSFER' "
            + "THEN COALESCE(g.type, 'goal') ELSE i.source_ref END, i.amount_paise, i.done_at "
            + "FROM checklist_item i LEFT JOIN loan l ON i.type = 'EMI' AND l.id::text = i.source_ref "
            + "LEFT JOIN goal g ON i.type = 'GOAL_TRANSFER' AND g.id::text = i.source_ref ";

    private static final RowMapper<ItemView> ITEM = (rs, n) -> {
        OffsetDateTime doneAt = rs.getObject(6, OffsetDateTime.class);
        return new ItemView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getLong(5), doneAt != null, doneAt == null ? null : doneAt.toInstant());
    };

    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;
    private final ConsentService consents;
    private final PlanCycleService cycles;

    @Autowired
    public ChecklistService(ObjectProvider<JdbcTemplate> jdbc, ObjectProvider<TransactionTemplate> tx,
            ConsentService consents, PlanCycleService cycles) {
        this(() -> required(jdbc.getIfAvailable()), () -> required(tx.getIfAvailable()), consents, cycles);
    }

    public ChecklistService(Supplier<JdbcTemplate> jdbc, Supplier<TransactionTemplate> tx, ConsentService consents,
            PlanCycleService cycles) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.consents = consents;
        this.cycles = cycles;
    }

    private static <T> T required(T bean) {
        if (bean == null) {
            throw new IllegalStateException("No datasource configured");
        }
        return bean;
    }

    /**
     * Inserts the missing items of the current OPEN cycle (one per EMI, per goal transfer line and per SIP
     * line) and returns the list. Existing keys are untouched, so repeat calls add nothing.
     */
    public ListView generate(UUID userId) {
        consents.require(userId);
        LocalDate start = cycles.current(userId).cycleStart();
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> {
            UUID cycleId = cycleId(j, userId, start);
            j.update("INSERT INTO checklist_item (user_id, plan_cycle_id, type, source_ref, amount_paise) "
                    + "SELECT l.user_id, ?, 'EMI', l.id::text, l.emi_paise FROM loan l "
                    + "WHERE l.user_id = ? AND l.emi_paise > 0 ON CONFLICT DO NOTHING", cycleId, userId);
            j.update("INSERT INTO checklist_item (user_id, plan_cycle_id, type, source_ref, amount_paise) "
                    + "SELECT g.user_id, p.id, 'GOAL_TRANSFER', g.id::text, (a.line ->> 'amountPaise')::bigint "
                    + "FROM plan_cycle p CROSS JOIN LATERAL jsonb_array_elements(p.allocations) AS a(line) "
                    + "JOIN goal g ON g.user_id = p.user_id AND g.id::text = a.line ->> 'ref' "
                    + "WHERE p.id = ? AND a.line ->> 'step' = 'SAFE_GOALS' "
                    + "AND (a.line ->> 'amountPaise')::bigint > 0 ON CONFLICT DO NOTHING", cycleId);
            j.update("INSERT INTO checklist_item (user_id, plan_cycle_id, type, source_ref, amount_paise) "
                    + "SELECT p.user_id, p.id, 'SIP', a.line ->> 'step', (a.line ->> 'amountPaise')::bigint "
                    + "FROM plan_cycle p CROSS JOIN LATERAL jsonb_array_elements(p.allocations) AS a(line) "
                    + "WHERE p.id = ? AND a.line ->> 'step' IN ('HABIT_SIP', 'LONG_TERM_SIP') "
                    + "AND (a.line ->> 'amountPaise')::bigint > 0 ON CONFLICT DO NOTHING", cycleId);
            return view(j, cycleId, start);
        });
    }

    /** The checklist of the current cycle, or of the cycle starting {@code cycleStart} when given. */
    public ListView list(UUID userId, LocalDate cycleStart) {
        consents.require(userId);
        LocalDate start = cycleStart != null ? cycleStart : cycles.current(userId).cycleStart();
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> view(j, cycleId(j, userId, start), start));
    }

    /** Marks the item done. The first done_at is kept on repeat calls. Unknown or foreign id: not found. */
    public ItemView markDone(UUID userId, UUID itemId) {
        consents.require(userId);
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> {
            j.update("UPDATE checklist_item SET done_at = COALESCE(done_at, now()) WHERE id = ? AND user_id = ?",
                    itemId, userId);
            return j.query(ITEM_SELECT + "WHERE i.id = ? AND i.user_id = ?", ITEM, itemId, userId).stream()
                    .findFirst().orElseThrow(ChecklistNotFoundException::new);
        });
    }

    /**
     * Streak over closed cycles plus the open one only when complete (an unfinished current month does
     * not reset it), via {@link Statistics#consistencyStreak}. History: the last cycles, oldest first,
     * the open cycle shown with its true state.
     */
    public StreakView streak(UUID userId) {
        consents.require(userId);
        cycles.current(userId);
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> {
            List<CycleRow> rows = j.query("SELECT cycle_start, status FROM plan_cycle WHERE user_id = ? "
                    + "AND status IN ('OPEN', 'CLOSED') ORDER BY cycle_start",
                    (rs, n) -> new CycleRow(rs.getObject(1, LocalDate.class), rs.getString(2)), userId);
            Map<LocalDate, List<Boolean>> flags = new LinkedHashMap<>();
            j.query("SELECT p.cycle_start, i.done_at IS NOT NULL FROM checklist_item i "
                    + "JOIN plan_cycle p ON p.id = i.plan_cycle_id WHERE i.user_id = ?", rs -> {
                        flags.computeIfAbsent(rs.getObject(1, LocalDate.class), k -> new ArrayList<>())
                                .add(rs.getBoolean(2));
                    }, userId);
            List<CycleFlag> history = new ArrayList<>();
            List<Boolean> forStreak = new ArrayList<>();
            for (CycleRow r : rows) {
                boolean done = Checklist.cycleComplete(flags.getOrDefault(r.cycleStart(), List.of()));
                history.add(new CycleFlag(r.cycleStart(), done));
                if (done || !"OPEN".equals(r.status())) {
                    forStreak.add(done);
                }
            }
            int from = Math.max(0, history.size() - HISTORY_CYCLES);
            return new StreakView(Statistics.consistencyStreak(forStreak),
                    List.copyOf(history.subList(from, history.size())));
        });
    }

    private UUID cycleId(JdbcTemplate j, UUID userId, LocalDate start) {
        return j.query("SELECT id FROM plan_cycle WHERE user_id = ? AND cycle_start = ?",
                (rs, n) -> rs.getObject(1, UUID.class), userId, start).stream().findFirst()
                .orElseThrow(ChecklistNotFoundException::new);
    }

    private ListView view(JdbcTemplate j, UUID cycleId, LocalDate start) {
        List<ItemView> items = j.query(ITEM_SELECT + "WHERE i.plan_cycle_id = ? ORDER BY i.type, i.source_ref",
                ITEM, cycleId);
        int done = count(j, cycleId, "i.done_at IS NOT NULL");
        int total = count(j, cycleId, "TRUE");
        return new ListView(start, items, done, total);
    }

    private static int count(JdbcTemplate j, UUID cycleId, String filter) {
        Integer n = j.queryForObject("SELECT count(*) FILTER (WHERE " + filter
                + ") FROM checklist_item i WHERE i.plan_cycle_id = ?", Integer.class, cycleId);
        return n == null ? 0 : n;
    }
}
