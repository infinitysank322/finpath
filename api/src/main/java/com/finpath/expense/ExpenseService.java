package com.finpath.expense;

import com.finpath.dpdp.ConsentService;
import com.finpath.expense.TransactionRepository.Draft;
import com.finpath.expense.TransactionRepository.InsertResult;
import com.finpath.expense.TransactionRepository.Transaction;
import com.finpath.rules.CycleCalendar;
import com.finpath.rules.TransactionHash;
import com.finpath.rules.TransactionHash.Direction;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expense CRUD over {@link TransactionRepository}. Expenses are the OUT rows of the transaction
 * table. No money arithmetic happens here: amounts are stored and returned as given, the hash is
 * the rules package's. Nothing here logs row content (the note is free text).
 */
@Service
public class ExpenseService {

    static final ZoneId CYCLE_ZONE = ZoneId.of("Asia/Kolkata");
    static final long MAX_RANGE_DAYS = 366;
    private static final Set<String> MODES = Set.of("UPI", "CARD", "CASH", "NETBANKING", "OTHER");
    private static final Set<String> TAGS = Set.of("NEED", "WANT", "WASTE");
    /** Categories that exist for direction IN only (V6 seed). */
    private static final Set<String> IN_ONLY = Set.of("INCOME", "REFUND", "OTHER_IN");
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,64}");

    /** Write payload. The owner is never part of it. */
    public record ExpenseInput(LocalDate txnDate, Long amountPaise, String categoryCode, String mode, String tag,
            String note) {
    }

    public record ExpenseView(UUID id, LocalDate txnDate, long amountPaise, String categoryCode, String mode,
            String tag, String source, String note, boolean possibleDuplicate) {
    }

    public record CreateResult(ExpenseView expense, boolean created) {
    }

    public record ListResult(LocalDate from, LocalDate toExclusive, List<ExpenseView> items) {
    }

    private final TransactionRepository repo;
    private final Supplier<JdbcTemplate> jdbc;
    private final Supplier<TransactionTemplate> tx;
    private final ConsentService consents;
    private final Clock clock;

    @Autowired
    public ExpenseService(TransactionRepository repo, ObjectProvider<JdbcTemplate> jdbc,
            ObjectProvider<TransactionTemplate> tx, ConsentService consents) {
        this(repo, () -> required(jdbc.getIfAvailable()), () -> required(tx.getIfAvailable()), consents,
                Clock.system(CYCLE_ZONE));
    }

    ExpenseService(TransactionRepository repo, Supplier<JdbcTemplate> jdbc, Supplier<TransactionTemplate> tx,
            ConsentService consents, Clock clock) {
        this.repo = repo;
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

    /**
     * Creates, or replays. A key that already exists returns the original row (created = false) when the
     * payload matches, and throws {@link IdempotencyConflictException} when it does not. A null key is
     * replaced by a server-generated one (such a create is not replayable).
     */
    public CreateResult create(UUID userId, String idempotencyKey, ExpenseInput in) {
        Draft d = validate(in, idempotencyKey == null ? UUID.randomUUID().toString() : checkKey(idempotencyKey));
        consents.require(userId);
        InsertResult r = insert(userId, d);
        if (!r.created() && !samePayload(r.row(), d)) {
            throw new IdempotencyConflictException();
        }
        return new CreateResult(views(userId, List.of(r.row())).get(0), r.created());
    }

    /** Expenses with {@code from <= date <= to}; with neither bound, the current cycle. */
    public ListResult list(UUID userId, LocalDate from, LocalDate to) {
        if ((from == null) != (to == null)) {
            throw new InvalidExpenseException();
        }
        LocalDate start = from;
        LocalDate endExclusive;
        if (from == null) {
            CycleCalendar.Cycle c = currentCycle(userId);
            start = c.start();
            endExclusive = c.endExclusive();
        } else {
            if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
                throw new InvalidExpenseException();
            }
            endExclusive = to.plusDays(1);
        }
        List<Transaction> rows = repo.listBetween(userId, start, endExclusive).stream()
                .filter(t -> t.direction() == Direction.OUT).toList();
        return new ListResult(start, endExclusive, views(userId, rows));
    }

    public ExpenseView replace(UUID userId, UUID id, ExpenseInput in) {
        Draft d = validate(in, "unused-on-update");
        consents.require(userId);
        Transaction existing = repo.findById(userId, id).filter(t -> t.direction() == Direction.OUT)
                .orElseThrow(ExpenseNotFoundException::new);
        Transaction row = repo.update(userId, existing.id(), d).orElseThrow(ExpenseNotFoundException::new);
        return views(userId, List.of(row)).get(0);
    }

    public void delete(UUID userId, UUID id) {
        consents.require(userId);
        Transaction existing = repo.findById(userId, id).filter(t -> t.direction() == Direction.OUT)
                .orElseThrow(ExpenseNotFoundException::new);
        if (!repo.delete(userId, existing.id())) {
            throw new ExpenseNotFoundException();
        }
    }

    private InsertResult insert(UUID userId, Draft d) {
        try {
            return repo.insert(userId, d);
        } catch (IllegalArgumentException unknownCategory) {
            throw new InvalidExpenseException();
        }
    }

    private static boolean samePayload(Transaction row, Draft d) {
        String hash = TransactionHash.dedupeHash(d.txnDate(), d.amountPaise(), d.direction(), d.categoryCode(),
                d.mode());
        return hash.equals(row.dedupeHash()) && Objects.equals(row.tag(), d.tag())
                && Objects.equals(row.note(), d.note());
    }

    private List<ExpenseView> views(UUID userId, List<Transaction> rows) {
        Set<String> repeated = repo.repeatedHashes(userId, rows.stream().map(Transaction::dedupeHash).distinct()
                .toList());
        return rows.stream().map(t -> new ExpenseView(t.id(), t.txnDate(), t.amountPaise(), t.categoryCode(),
                t.mode(), t.tag(), t.source(), t.note(), repeated.contains(t.dedupeHash()))).toList();
    }

    private CycleCalendar.Cycle currentCycle(UUID userId) {
        JdbcTemplate j = jdbc.get();
        List<Integer> days = tx.get().execute(status -> j.query("SELECT cycle_start_day FROM users WHERE id = ?",
                (rs, n) -> rs.getInt(1), userId));
        if (days == null || days.isEmpty()) {
            throw new ExpenseNotFoundException();
        }
        return CycleCalendar.cycleFor(LocalDate.now(clock), days.get(0));
    }

    private static String checkKey(String key) {
        if (!KEY.matcher(key).matches()) {
            throw new InvalidExpenseException();
        }
        return key;
    }

    private static Draft validate(ExpenseInput in, String key) {
        if (in == null || in.txnDate() == null || in.amountPaise() == null || in.amountPaise() <= 0
                || in.categoryCode() == null || in.mode() == null) {
            throw new InvalidExpenseException();
        }
        String category = in.categoryCode().trim().toUpperCase(java.util.Locale.ROOT);
        String mode = in.mode().trim().toUpperCase(java.util.Locale.ROOT);
        if (category.isEmpty() || IN_ONLY.contains(category) || !MODES.contains(mode)) {
            throw new InvalidExpenseException();
        }
        String tag = in.tag() == null ? null : in.tag().trim().toUpperCase(java.util.Locale.ROOT);
        if (tag != null && !TAGS.contains(tag)) {
            throw new InvalidExpenseException();
        }
        return new Draft(in.txnDate(), in.amountPaise(), Direction.OUT, category, mode, tag,
                NoteGuard.check(in.note()), key);
    }
}
