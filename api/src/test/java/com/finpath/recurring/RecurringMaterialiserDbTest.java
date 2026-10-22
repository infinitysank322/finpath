package com.finpath.recurring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finpath.auth.CurrentUser;
import com.finpath.db.TestDb;
import com.finpath.dpdp.ConsentRequiredException;
import com.finpath.dpdp.ConsentService;
import com.finpath.dpdp.ConsentStatement;
import com.finpath.expense.TransactionRepository;
import com.finpath.recurring.RecurringService.MaterialiseResult;
import com.finpath.recurring.RecurringService.TemplateInput;
import com.finpath.recurring.RecurringService.TemplateView;
import com.finpath.rls.RlsTransactionManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/** Service + repository against the real scratch database under RLS (skipped without one). */
class RecurringMaterialiserDbTest {

    private static final LocalDate SEP = LocalDate.parse("2026-09-25");
    private static final LocalDate OCT = LocalDate.parse("2026-10-25");
    private static final LocalDate NOV = LocalDate.parse("2026-11-25");
    private static final LocalDate DEC = LocalDate.parse("2026-12-25");

    private RecurringService service;
    private ConsentService consents;
    private UUID a;
    private UUID b;

    @BeforeEach
    void setUp() throws SQLException {
        TestDb.requireAndMigrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(TestDb.URL, TestDb.USER, TestDb.PASSWORD);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new RlsTransactionManager(ds, CurrentUser::id));
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("jdbc", jdbc);
        bf.registerSingleton("tx", tx);
        consents = new ConsentService(bf.getBeanProvider(JdbcTemplate.class),
                bf.getBeanProvider(TransactionTemplate.class));
        TransactionRepository repo = new TransactionRepository(() -> jdbc, () -> tx);
        Clock clock = Clock.fixed(Instant.parse("2026-10-30T06:30:00Z"), ZoneId.of("Asia/Kolkata"));
        service = new RecurringService(repo, () -> jdbc, () -> tx, consents, clock);
        a = newUser(true);
        b = newUser(true);
    }

    @AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    private UUID newUser(boolean consent) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, name, email, cycle_start_day) VALUES (?, 'T', ?, 25)")) {
            ps.setObject(1, id);
            ps.setString(2, id + "@example.test");
            ps.executeUpdate();
        }
        if (consent) {
            as(id);
            consents.grant(id, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
        }
        return id;
    }

    private static void as(UUID user) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(user.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private TemplateView quarterly(UUID user, long bill) {
        as(user);
        return service.create(user, new TemplateInput("utilities", bill, "QUARTERLY", "upi", "NEED", SEP, null));
    }

    private long[] postings(UUID user) throws SQLException {
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "SELECT count(*), COALESCE(sum(amount_paise), 0), count(*) FILTER (WHERE source = 'RECURRING' "
                        + "AND note IS NULL AND direction = 'OUT') FROM transaction WHERE user_id = ?")) {
            ps.setObject(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)};
            }
        }
    }

    @Test
    void quarterlyBillPostsOneInstalmentPerCycleAndSumsToTheBill() throws SQLException {
        TemplateView v = quarterly(a, 600_000);
        assertThat(v.perCycleSpread()).extracting(RecurringService.SpreadItem::amountPaise)
                .containsExactly(200_000L, 200_000L, 200_000L);
        for (LocalDate day : List.of(SEP.plusDays(3), OCT.plusDays(3), NOV.plusDays(3))) {
            MaterialiseResult r = service.materialiseForUser(a, day);
            assertThat(r.createdCount()).isEqualTo(1);
            assertThat(r.postedPaise()).isEqualTo(200_000L);
        }
        long[] p = postings(a);
        assertThat(p[0]).isEqualTo(3);
        assertThat(p[1]).isEqualTo(600_000L);
        assertThat(p[2]).isEqualTo(3);
        // The fourth cycle starts the next period.
        assertThat(service.materialiseForUser(a, DEC).items().get(0).instalmentIndex()).isZero();
    }

    @Test
    void indivisibleBillPosts333_333_334() throws SQLException {
        quarterly(a, 1000);
        long[] expected = {333, 333, 334};
        LocalDate[] days = {SEP, OCT, NOV};
        for (int i = 0; i < 3; i++) {
            assertThat(service.materialiseForUser(a, days[i]).postedPaise()).isEqualTo(expected[i]);
        }
        assertThat(postings(a)[1]).isEqualTo(1000);
    }

    @Test
    void runningTwiceInTheSameCycleMaterialisesOnce() throws SQLException {
        quarterly(a, 600_000);
        MaterialiseResult first = service.materialiseForUser(a, OCT);
        MaterialiseResult second = service.materialiseForUser(a, OCT.plusDays(10));
        assertThat(first.createdCount()).isEqualTo(1);
        assertThat(second.createdCount()).isZero();
        assertThat(second.postedPaise()).isZero();
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().get(0).created()).isFalse();
        assertThat(postings(a)[0]).isEqualTo(1);
    }

    @Test
    void endpointStyleMaterialiseUsesTheClockAndIsIdempotent() throws SQLException {
        quarterly(a, 600_000);   // clock is 2026-10-30: cycle starting 2026-10-25
        as(a);
        assertThat(service.materialise(a).createdCount()).isEqualTo(1);
        assertThat(service.materialise(a).createdCount()).isZero();
        assertThat(postings(a)[0]).isEqualTo(1);
    }

    @Test
    void bNeverTouchesAsTemplates() throws SQLException {
        UUID templateOfA = quarterly(a, 600_000).id();
        as(b);
        MaterialiseResult r = service.materialiseForUser(b, OCT);
        assertThat(r.items()).isEmpty();
        assertThat(postings(b)[0]).isZero();
        assertThat(postings(a)[0]).isZero();
        assertThat(service.list(b)).isEmpty();
        assertThatThrownBy(() -> service.delete(b, templateOfA)).isInstanceOf(RecurringNotFoundException.class);
        assertThatThrownBy(() -> service.replace(b, templateOfA, new TemplateInput("utilities", 600_000L,
                "QUARTERLY", "upi", null, SEP, null))).isInstanceOf(RecurringNotFoundException.class);
    }

    @Test
    void deletingATemplateKeepsItsPostings() throws SQLException {
        TemplateView v = quarterly(a, 600_000);
        service.materialiseForUser(a, OCT);
        service.delete(a, v.id());
        assertThat(service.list(a)).isEmpty();
        assertThat(postings(a)[0]).isEqualTo(1);
    }

    @Test
    void writesAndMaterialiseNeedConsent() throws SQLException {
        UUID c = newUser(false);
        as(c);
        TemplateInput in = new TemplateInput("utilities", 600_000L, "QUARTERLY", "upi", null, SEP, null);
        assertThatThrownBy(() -> service.create(c, in)).isInstanceOf(ConsentRequiredException.class);
        assertThatThrownBy(() -> service.materialise(c)).isInstanceOf(ConsentRequiredException.class);
    }

    @Test
    void invalidInputIsRejected() {
        as(a);
        String[][] bad = {{"nope", "QUARTERLY", "UPI"}, {"INCOME", "QUARTERLY", "UPI"},
                {"UTILITIES", "WEEKLY", "UPI"}, {"UTILITIES", "QUARTERLY", "BARTER"}};
        for (String[] f : bad) {
            assertThatThrownBy(() -> service.create(a, new TemplateInput(f[0], 600_000L, f[1], f[2], null, SEP,
                    null))).isInstanceOf(InvalidRecurringException.class);
        }
        for (Long amount : new Long[] {0L, -5L, 2L, null}) {   // 2 paise cannot cover 3 instalments
            assertThatThrownBy(() -> service.create(a, new TemplateInput("UTILITIES", amount, "QUARTERLY", "UPI",
                    null, SEP, null))).isInstanceOf(InvalidRecurringException.class);
        }
        assertThatThrownBy(() -> service.create(a, new TemplateInput("UTILITIES", 600_000L, "QUARTERLY", "UPI",
                "BAD", SEP, null))).isInstanceOf(InvalidRecurringException.class);
        assertThatThrownBy(() -> service.create(a, new TemplateInput("UTILITIES", 600_000L, "QUARTERLY", "UPI",
                null, SEP, SEP.minusDays(1)))).isInstanceOf(InvalidRecurringException.class);
    }
}
