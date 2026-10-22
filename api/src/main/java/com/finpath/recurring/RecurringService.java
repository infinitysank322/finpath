package com.finpath.recurring;

import com.finpath.dpdp.ConsentService;
import com.finpath.expense.TransactionRepository;
import com.finpath.expense.TransactionRepository.Draft;
import com.finpath.expense.TransactionRepository.InsertResult;
import com.finpath.rules.CycleCalendar;
import com.finpath.rules.RecurringSplit;
import com.finpath.rules.RecurringSplit.Frequency;
import com.finpath.rules.TransactionHash.Direction;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recurring bill templates and their materialisation into transactions. The split comes from
 * {@link RecurringSplit}; this class fetches rows and writes postings. Templates carry no free text,
 * and nothing here logs row content or amounts.
 */
@Service
public class RecurringService {

    static final ZoneId CYCLE_ZONE = ZoneId.of("Asia/Kolkata");
    static final String SOURCE = "RECURRING";
    private static final Set<String> MODES = Set.of("UPI", "CARD", "CASH", "NETBANKING", "OTHER");
    private static final Set<String> TAGS = Set.of("NEED", "WANT", "WASTE");
    private static final Set<String> IN_ONLY = Set.of("INCOME", "REFUND", "OTHER_IN");
    private static final DateTimeFormatter KEY_DATE = DateTimeFormatter.BASIC_ISO_DATE;

    /** Write payload. The owner is never part of it. Enums arrive as text so a bad value is a 400, not a parse error. */
    public record TemplateInput(String categoryCode, Long amountPaise, String frequency, String mode, String tag,
            LocalDate startDate, LocalDate endDate) {
    }

    public record SpreadItem(int index, long amountPaise) {
    }

    public record TemplateView(UUID id, String categoryCode, long amountPaise, Frequency frequency, String mode,
            String tag, LocalDate startDate, LocalDate endDate, List<SpreadItem> perCycleSpread,
            Long currentInstalmentPaise) {
    }

    public record PostedItem(UUID templateId, int instalmentIndex, int instalments, long amountPaise,
            boolean created) {
    }

    public record MaterialiseResult(LocalDate cycleStart, LocalDate cycleEndExclusive, List<PostedItem> items,
            int createdCount, long postedPaise) {
    }

    private record Row(UUID id, String categoryCode, RecurringSplit.Template template, String mode, String tag) {
    }

    private final TransactionRepository transactions;
    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;
    private final ConsentService consents;
    private final Clock clock;

    @Autowired
    public RecurringService(TransactionRepository transactions, ObjectProvider<JdbcTemplate> jdbc,
            ObjectProvider<TransactionTemplate> tx, ConsentService consents) {
        this(transactions, () -> required(jdbc.getIfAvailable()), () -> required(tx.getIfAvailable()), consents,
                Clock.system(CYCLE_ZONE));
    }

    RecurringService(TransactionRepository transactions, Supplier<JdbcTemplate> jdbc,
            Supplier<TransactionTemplate> tx, ConsentService consents, Clock clock) {
        this.transactions = transactions;
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

    private record Valid(String category, RecurringSplit.Template template, String mode, String tag) {
    }

    public TemplateView create(UUID userId, TemplateInput in) {
        Valid v = validate(in);
        consents.require(userId);
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> {
            UUID categoryId = categoryId(j, v.category());
            UUID id = j.queryForObject("INSERT INTO recurring_template (user_id, category_id, amount_paise, "
                    + "frequency, mode, tag, start_date, end_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                    UUID.class, userId, categoryId, v.template().billPaise(), v.template().frequency().name(),
                    v.mode(), v.tag(), v.template().startDate(), v.template().endDate());
            return view(new Row(id, v.category(), v.template(), v.mode(), v.tag()), cycle(j, userId));
        });
    }

    /** All the user's templates, newest start date first. */
    public List<TemplateView> list(UUID userId) {
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> {
            int startDay = startDay(j, userId);
            CycleCalendar.Cycle cycle = CycleCalendar.cycleFor(LocalDate.now(clock), startDay);
            return rows(j, userId).stream().map(r -> view(r, cycle, startDay)).toList();
        });
    }

    public TemplateView replace(UUID userId, UUID id, TemplateInput in) {
        Valid v = validate(in);
        consents.require(userId);
        JdbcTemplate j = jdbc.get();
        return tx.get().execute(status -> {
            UUID categoryId = categoryId(j, v.category());
            int n = j.update("UPDATE recurring_template SET category_id = ?, amount_paise = ?, frequency = ?, "
                    + "mode = ?, tag = ?, start_date = ?, end_date = ? WHERE id = ? AND user_id = ?", categoryId,
                    v.template().billPaise(), v.template().frequency().name(), v.mode(), v.tag(),
                    v.template().startDate(), v.template().endDate(), id, userId);
            if (n == 0) {
                throw new RecurringNotFoundException();
            }
            return view(new Row(id, v.category(), v.template(), v.mode(), v.tag()), cycle(j, userId));
        });
    }

    /** Deletes the template only; postings already made stay (they are the user's own records). */
    public void delete(UUID userId, UUID id) {
        consents.require(userId);
        JdbcTemplate j = jdbc.get();
        tx.get().executeWithoutResult(status -> {
            if (j.update("DELETE FROM recurring_template WHERE id = ? AND user_id = ?", id, userId) == 0) {
                throw new RecurringNotFoundException();
            }
        });
    }

    public MaterialiseResult materialise(UUID userId) {
        consents.require(userId);
        return materialiseForUser(userId, LocalDate.now(clock));
    }

    /**
     * Posts this cycle's instalment of every due template. The key is deterministic per template and
     * cycle, so a second run finds the unique (user, key) row and creates nothing.
     */
    public MaterialiseResult materialiseForUser(UUID userId, LocalDate today) {
        JdbcTemplate j = jdbc.get();
        int startDay;
        List<Row> rows;
        {
            record Loaded(int startDay, List<Row> rows) {
            }
            Loaded l = tx.get().execute(status -> new Loaded(startDay(j, userId), rows(j, userId)));
            startDay = l.startDay();
            rows = l.rows();
        }
        CycleCalendar.Cycle cycle = CycleCalendar.cycleFor(today, startDay);
        List<PostedItem> items = new ArrayList<>();
        long posted = 0;
        int createdCount = 0;
        for (Row r : rows) {
            Optional<RecurringSplit.Due> due = RecurringSplit.due(r.template(), cycle, startDay);
            if (due.isEmpty()) {
                continue;
            }
            RecurringSplit.Due d = due.get();
            String key = "rt-" + r.id() + "-" + KEY_DATE.format(cycle.start());
            InsertResult res = transactions.insert(userId, new Draft(cycle.start(), d.amountPaise(), Direction.OUT,
                    r.categoryCode(), r.mode(), r.tag(), null, key, SOURCE));
            if (res.created()) {
                createdCount++;
                posted += d.amountPaise();
            }
            items.add(new PostedItem(r.id(), d.index(), d.periods(), d.amountPaise(), res.created()));
        }
        return new MaterialiseResult(cycle.start(), cycle.endExclusive(), items, createdCount, posted);
    }

    private static Valid validate(TemplateInput in) {
        if (in == null || in.categoryCode() == null || in.amountPaise() == null || in.frequency() == null
                || in.mode() == null || in.startDate() == null) {
            throw new InvalidRecurringException();
        }
        String category = in.categoryCode().trim().toUpperCase(Locale.ROOT);
        String mode = in.mode().trim().toUpperCase(Locale.ROOT);
        if (category.isEmpty() || IN_ONLY.contains(category) || !MODES.contains(mode)) {
            throw new InvalidRecurringException();
        }
        String tag = in.tag() == null ? null : in.tag().trim().toUpperCase(Locale.ROOT);
        if (tag != null && !TAGS.contains(tag)) {
            throw new InvalidRecurringException();
        }
        Frequency freq;
        try {
            freq = Frequency.valueOf(in.frequency().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRecurringException();
        }
        long bill = in.amountPaise();
        if (bill <= 0 || !RecurringSplit.splittable(bill, freq)
                || (in.endDate() != null && in.endDate().isBefore(in.startDate()))) {
            throw new InvalidRecurringException();
        }
        return new Valid(category, new RecurringSplit.Template(bill, freq, in.startDate(), in.endDate()), mode, tag);
    }

    private static UUID categoryId(JdbcTemplate j, String code) {
        List<UUID> ids = j.queryForList("SELECT id FROM category WHERE code = ?", UUID.class, code);
        if (ids.isEmpty()) {
            throw new InvalidRecurringException();
        }
        return ids.get(0);
    }

    private static int startDay(JdbcTemplate j, UUID userId) {
        List<Integer> days = j.query("SELECT cycle_start_day FROM users WHERE id = ?", (rs, n) -> rs.getInt(1),
                userId);
        if (days.isEmpty()) {
            throw new RecurringNotFoundException();
        }
        return days.get(0);
    }

    private record CycleInfo(CycleCalendar.Cycle cycle, int startDay) {
    }

    private CycleInfo cycle(JdbcTemplate j, UUID userId) {
        int startDay = startDay(j, userId);
        return new CycleInfo(CycleCalendar.cycleFor(LocalDate.now(clock), startDay), startDay);
    }

    private static List<Row> rows(JdbcTemplate j, UUID userId) {
        return j.query("SELECT t.id, c.code, t.amount_paise, t.frequency, t.mode, t.tag, t.start_date, t.end_date "
                + "FROM recurring_template t JOIN category c ON c.id = t.category_id WHERE t.user_id = ? "
                + "ORDER BY t.start_date DESC, t.created_at DESC, t.id", (rs, n) -> new Row(
                        rs.getObject(1, UUID.class), rs.getString(2),
                        new RecurringSplit.Template(rs.getLong(3), Frequency.valueOf(rs.getString(4)),
                                rs.getObject(7, LocalDate.class), rs.getObject(8, LocalDate.class)),
                        rs.getString(5), rs.getString(6)), userId);
    }

    private static TemplateView view(Row r, CycleInfo ci) {
        return view(r, ci.cycle(), ci.startDay());
    }

    private static TemplateView view(Row r, CycleCalendar.Cycle cycle, int startDay) {
        RecurringSplit.Template t = r.template();
        int periods = RecurringSplit.periodMonths(t.frequency());
        List<SpreadItem> spread = new ArrayList<>();
        for (int i = 0; i < periods; i++) {
            spread.add(new SpreadItem(i, RecurringSplit.instalment(t.billPaise(), periods, i)));
        }
        Long current = RecurringSplit.due(t, cycle, startDay).map(RecurringSplit.Due::amountPaise).orElse(null);
        return new TemplateView(r.id(), r.categoryCode(), t.billPaise(), t.frequency(), r.mode(), r.tag(),
                t.startDate(), t.endDate(), List.copyOf(spread), current);
    }
}
