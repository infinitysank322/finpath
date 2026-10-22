package com.finpath.expense;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.finpath.api.LogCapture;
import com.finpath.auth.CurrentUser;
import com.finpath.auth.CurrentUserIdArgumentResolver;
import com.finpath.db.TestDb;
import com.finpath.dpdp.ConsentService;
import com.finpath.dpdp.ConsentStatement;
import com.finpath.rls.RlsTransactionManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Controller + service + repository + exception advice against the real scratch database under RLS
 * (skipped without one). The principal is set in the security context exactly where the JWT filter
 * would put it, and RLS binds the same id. Also records the warm create latency (T37 acceptance).
 */
class ExpenseEndpointDbTest {

    private MockMvc mvc;
    private ConsentService consents;
    private JdbcTemplate jdbc;
    private UUID a;
    private UUID b;

    @BeforeEach
    void setUp() throws SQLException {
        TestDb.requireAndMigrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(TestDb.URL, TestDb.USER, TestDb.PASSWORD);
        jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new RlsTransactionManager(ds, CurrentUser::id));
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("jdbc", jdbc);
        bf.registerSingleton("tx", tx);
        consents = new ConsentService(bf.getBeanProvider(JdbcTemplate.class),
                bf.getBeanProvider(TransactionTemplate.class));
        TransactionRepository repo = new TransactionRepository(() -> jdbc, () -> tx);
        Clock clock = Clock.fixed(Instant.parse("2026-10-22T06:30:00Z"), ZoneId.of("Asia/Kolkata"));
        ExpenseService service = new ExpenseService(repo, () -> jdbc, () -> tx, consents, clock);
        ObjectMapper om = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);   // as Spring Boot does
        mvc = MockMvcBuilders.standaloneSetup(new ExpenseController(service))
                .setControllerAdvice(new ExpenseExceptionHandler())
                .setCustomArgumentResolvers(new CurrentUserIdArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om)).build();
        a = newUser();
        b = newUser();
        as(a);
        consents.grant(a, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
        as(b);
        consents.grant(b, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
    }

    @org.junit.jupiter.api.AfterEach
    void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    private static UUID newUser() throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, name, email) VALUES (?, 'T', ?)")) {
            ps.setObject(1, id);
            ps.setString(2, id + "@example.test");
            ps.executeUpdate();
        }
        return id;
    }

    private static void as(UUID user) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(user.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    private static String body(long paise, String extra) {
        return "{\"txnDate\":\"2026-10-22\",\"amountPaise\":" + paise
                + ",\"categoryCode\":\"dining\",\"mode\":\"upi\"" + extra + "}";
    }

    private MockHttpServletRequestBuilder create(String key, String content) {
        MockHttpServletRequestBuilder r = post("/expenses").contentType(MediaType.APPLICATION_JSON).content(content);
        return key == null ? r : r.header("Idempotency-Key", key);
    }

    private long count(UUID user) throws SQLException {
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM transaction WHERE user_id = ?")) {
            ps.setObject(1, user);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static String idOf(MvcResult r) throws Exception {
        return new ObjectMapper().readTree(r.getResponse().getContentAsString()).get("id").asText();
    }

    @Test
    void replayWithTheSameKeyReturnsTheOriginalRowAndInsertsNothing() throws Exception {
        as(a);
        MvcResult first = mvc.perform(create("key-00000001", body(4000, ""))).andExpect(status().isCreated())
                .andReturn();
        MvcResult replay = mvc.perform(create("key-00000001", body(4000, ""))).andExpect(status().isOk())
                .andReturn();
        assertThat(idOf(replay)).isEqualTo(idOf(first));
        assertThat(count(a)).isEqualTo(1);
    }

    @Test
    void sameKeyDifferentPayloadIs409AndTheOriginalIsUntouched() throws Exception {
        as(a);
        mvc.perform(create("key-00000001", body(4000, ""))).andExpect(status().isCreated());
        mvc.perform(create("key-00000001", body(4001, ""))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("idempotency_conflict")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(create("key-00000001", body(4000, ",\"note\":\"changed\""))).andExpect(status().isConflict());
        mvc.perform(create("key-00000001", body(4000, ",\"tag\":\"WANT\""))).andExpect(status().isConflict());
        assertThat(count(a)).isEqualTo(1);
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "SELECT amount_paise FROM transaction WHERE user_id = ?")) {
            ps.setObject(1, a);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1)).isEqualTo(4000);
            }
        }
    }

    @Test
    void theSameKeyMayBeUsedByTwoUsersIndependently() throws Exception {
        as(a);
        mvc.perform(create("key-00000001", body(4000, ""))).andExpect(status().isCreated());
        as(b);
        mvc.perform(create("key-00000001", body(4000, ""))).andExpect(status().isCreated());
        assertThat(count(a)).isEqualTo(1);
        assertThat(count(b)).isEqualTo(1);
    }

    @Test
    void aMissingKeyIsGeneratedServerSideAndABadKeyIs400() throws Exception {
        as(a);
        mvc.perform(create(null, body(4000, ""))).andExpect(status().isCreated());
        mvc.perform(create(null, body(4000, ""))).andExpect(status().isCreated());   // not replayable
        mvc.perform(create("short", body(4000, ""))).andExpect(status().isBadRequest());
        mvc.perform(create("bad key with spaces", body(4000, ""))).andExpect(status().isBadRequest());
        assertThat(count(a)).isEqualTo(2);
    }

    @Test
    void duplicateHintIsRaisedButNothingIsBlocked() throws Exception {
        as(a);
        mvc.perform(create("key-00000001", body(4000, ""))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.possibleDuplicate").value(false));
        mvc.perform(create("key-00000002", body(4000, ",\"note\":\"second\""))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.possibleDuplicate").value(true));
        mvc.perform(create("key-00000003", body(4001, ""))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.possibleDuplicate").value(false));
        mvc.perform(get("/expenses").param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[?(@.amountPaise==4000 && @.possibleDuplicate==true)]",
                        org.hamcrest.Matchers.hasSize(2)));
        // Another user's identical payment is not a duplicate for me.
        as(b);
        mvc.perform(create("key-00000001", body(4000, ""))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.possibleDuplicate").value(false));
    }

    @Test
    void crossTenantReadUpdateAndDeleteThroughTheEndpointAre404() throws Exception {
        as(a);
        String id = idOf(mvc.perform(create("key-00000001", body(4000, ""))).andReturn());
        as(b);
        mvc.perform(get("/expenses").param("from", "2026-10-01").param("to", "2026-10-31"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/expenses")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(put("/expenses/" + id).contentType(MediaType.APPLICATION_JSON).content(body(1, "")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.error").value("not_found"));
        mvc.perform(delete("/expenses/" + id)).andExpect(status().isNotFound());
        // Replaying A's key as B creates B's own row; it does not reveal or return A's.
        MvcResult mine = mvc.perform(create("key-00000001", body(4000, ""))).andExpect(status().isCreated())
                .andReturn();
        assertThat(idOf(mine)).isNotEqualTo(id);
        // A body userId is ignored.
        mvc.perform(create("key-00000009", body(500, ",\"userId\":\"" + a + "\""))).andExpect(status().isCreated());
        assertThat(count(a)).isEqualTo(1);
        assertThat(count(b)).isEqualTo(2);
        as(a);
        mvc.perform(get("/expenses")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(id)).andExpect(jsonPath("$.items[0].amountPaise").value(4000));
    }

    @Test
    void updateAndDeleteWorkForTheOwnerAndRecomputeTheDuplicateHint() throws Exception {
        as(a);
        String first = idOf(mvc.perform(create("key-00000001", body(4000, ""))).andReturn());
        mvc.perform(create("key-00000002", body(4000, ""))).andExpect(jsonPath("$.possibleDuplicate").value(true));
        mvc.perform(put("/expenses/" + first).contentType(MediaType.APPLICATION_JSON)
                .content(body(5000, ",\"tag\":\"need\",\"note\":\"edited\"")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amountPaise").value(5000))
                .andExpect(jsonPath("$.tag").value("NEED")).andExpect(jsonPath("$.possibleDuplicate").value(false));
        mvc.perform(delete("/expenses/" + first)).andExpect(status().isNoContent());
        mvc.perform(delete("/expenses/" + first)).andExpect(status().isNotFound());
        assertThat(count(a)).isEqualTo(1);
    }

    @Test
    void listDefaultsToTheCurrentCycleAndRangesAreInclusive() throws Exception {
        as(a);
        // Default start day 25 and "today" 2026-10-22 (IST): cycle is 25 Sep .. 24 Oct.
        for (String d : List.of("2026-09-24", "2026-09-25", "2026-10-24", "2026-10-25")) {
            mvc.perform(create("key-" + d, "{\"txnDate\":\"" + d
                    + "\",\"amountPaise\":100,\"categoryCode\":\"DINING\",\"mode\":\"CASH\"}"))
                    .andExpect(status().isCreated());
        }
        mvc.perform(get("/expenses")).andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-09-25")).andExpect(jsonPath("$.toExclusive").value("2026-10-25"))
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/expenses").param("from", "2026-10-24").param("to", "2026-10-25"))
                .andExpect(jsonPath("$.items.length()").value(2));
        mvc.perform(get("/expenses").param("from", "2026-10-25").param("to", "2026-10-24"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/expenses").param("from", "2026-10-25")).andExpect(status().isBadRequest());
        mvc.perform(get("/expenses").param("from", "2024-01-01").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void noConsentIs403AndWritesNothing() throws Exception {
        UUID c = newUser();
        as(c);
        mvc.perform(create("key-00000001", body(4000, ""))).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("consent_required"));
        mvc.perform(delete("/expenses/" + UUID.randomUUID())).andExpect(status().isForbidden());
        assertThat(count(c)).isZero();
    }

    @Test
    void invalidInputIs400AndTheNoteIsNeverEchoedOrLogged() throws Exception {
        as(a);
        String secretNote = "acct 123456789012";
        try (LogCapture logs = LogCapture.start()) {
            MvcResult r = mvc.perform(create("key-00000001", body(4000, ",\"note\":\"" + secretNote + "\"")))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_request"))
                    .andExpect(jsonPath("$.length()").value(2)).andReturn();
            assertThat(r.getResponse().getContentAsString()).doesNotContain("1234");
            assertThat(logs.text()).doesNotContain("123456789012");
        }
        for (String bad : new String[] {
            body(4000, ",\"note\":\"PAN ABCDE1234F\""),
            body(0, ""), body(-5, ""),
            "{\"txnDate\":\"2026-10-22\",\"amountPaise\":100,\"categoryCode\":\"NOPE\",\"mode\":\"UPI\"}",
            "{\"txnDate\":\"2026-10-22\",\"amountPaise\":100,\"categoryCode\":\"INCOME\",\"mode\":\"UPI\"}",
            "{\"txnDate\":\"2026-10-22\",\"amountPaise\":100,\"categoryCode\":\"DINING\",\"mode\":\"BARTER\"}",
            body(100, ",\"tag\":\"SPLURGE\""),
            body(100, ",\"note\":\"" + "x".repeat(141) + "\""),
            body(100, "").replace("100", "12.5")}) {
            mvc.perform(create(null, bad)).andExpect(status().isBadRequest());
        }
        assertThat(count(a)).isZero();
    }

    @Test
    void aDatabaseConstraintFailureAnswersWithAGenericBodyAndNoRowTextInTheLog() throws Exception {
        as(a);
        // Defeat the service-level validation by writing past it: an over-long note straight to the
        // repository path is impossible, so provoke a constraint error with a mode the CHECK rejects.
        TransactionRepository repo = new TransactionRepository(() -> jdbc, () -> new TransactionTemplate(
                new RlsTransactionManager(new DriverManagerDataSource(TestDb.URL, TestDb.USER, TestDb.PASSWORD),
                        CurrentUser::id)));
        try (LogCapture logs = LogCapture.start()) {
            // Mirror the application.yml pin (proved by ExpenseLogPinTest), which a standalone test does not load.
            for (String name : List.of("org.springframework.transaction", "org.springframework.jdbc")) {
                ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(name))
                        .setLevel(ch.qos.logback.classic.Level.INFO);
            }
            try {
                repo.insert(a, new TransactionRepository.Draft(LocalDate.of(2026, 10, 22), 100,
                        com.finpath.rules.TransactionHash.Direction.OUT, "DINING", "BARTER", null, "secret-note-xyz",
                        "key-00000001"));
            } catch (org.springframework.dao.DataAccessException expected) {
                ExpenseExceptionHandler h = new ExpenseExceptionHandler();
                var resp = h.dataAccess(expected);
                assertThat(resp.getStatusCode().value()).isEqualTo(500);
                assertThat(resp.getBody()).isEqualTo(Map.of("status", 500, "error", "server_error"));
            }
            assertThat(logs.text()).doesNotContain("secret-note-xyz");
        }
    }

    @Test
    void tagPatchSetsClearsAndRejectsAndTheSummaryAddsUpWithUntaggedSeparate() throws Exception {
        as(a);
        String need = idOf(mvc.perform(create("key-00000001", body(1000, ",\"tag\":\"NEED\""))).andReturn());
        String untagged = idOf(mvc.perform(create("key-00000002", body(400, ""))).andReturn());
        String waste = idOf(mvc.perform(create("key-00000003", body(75, ""))).andReturn());
        mvc.perform(patch("/expenses/" + waste + "/tag").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tag\":\"waste\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.tag").value("WASTE"));
        mvc.perform(get("/expenses/tag-summary")).andExpect(status().isOk())
                .andExpect(jsonPath("$.needPaise").value(1000)).andExpect(jsonPath("$.wantPaise").value(0))
                .andExpect(jsonPath("$.wastePaise").value(75)).andExpect(jsonPath("$.untaggedPaise").value(400))
                .andExpect(jsonPath("$.totalPaise").value(1475));
        mvc.perform(patch("/expenses/" + need + "/tag").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tag\":null}")).andExpect(status().isOk()).andExpect(jsonPath("$.tag").doesNotExist());
        mvc.perform(get("/expenses/tag-summary")).andExpect(jsonPath("$.needPaise").value(0))
                .andExpect(jsonPath("$.untaggedPaise").value(1400)).andExpect(jsonPath("$.totalPaise").value(1475));
        mvc.perform(patch("/expenses/" + untagged + "/tag").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tag\":\"LUXURY\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/expenses/tag-summary").param("from", "2026-10-01")).andExpect(status().isBadRequest());
    }

    @Test
    void tagPatchAndSummaryAreTenantScopedAndNeedConsent() throws Exception {
        as(a);
        String id = idOf(mvc.perform(create("key-00000001", body(1000, ""))).andReturn());
        as(b);
        mvc.perform(patch("/expenses/" + id + "/tag").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tag\":\"NEED\"}")).andExpect(status().isNotFound());
        mvc.perform(get("/expenses/tag-summary")).andExpect(jsonPath("$.totalPaise").value(0));
        as(a);
        mvc.perform(get("/expenses/tag-summary")).andExpect(jsonPath("$.untaggedPaise").value(1000));
        consents.withdraw(a);
        mvc.perform(patch("/expenses/" + id + "/tag").contentType(MediaType.APPLICATION_JSON)
                .content("{\"tag\":\"NEED\"}")).andExpect(status().isForbidden());
    }

    /** T37 acceptance: warm p95 of POST /expenses under 500 ms. The measured figure is printed and asserted. */
    @Test
    void warmCreateP95IsUnder500Ms() throws Exception {
        as(a);
        for (int i = 0; i < 20; i++) {   // warm-up: JIT, pool, first-query costs
            mvc.perform(create("warm-" + String.format("%08d", i), body(100 + i, ""))).andExpect(status().isCreated());
        }
        int n = 200;
        List<Long> micros = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            MockHttpServletRequestBuilder req = create("meas-" + String.format("%08d", i), body(1000 + i, ""));
            long t0 = System.nanoTime();
            mvc.perform(req).andExpect(status().isCreated());
            micros.add((System.nanoTime() - t0) / 1000);
        }
        Collections.sort(micros);
        double p50 = micros.get(n / 2 - 1) / 1000.0;
        double p95 = micros.get((int) Math.ceil(n * 0.95) - 1) / 1000.0;
        double max = micros.get(n - 1) / 1000.0;
        System.out.printf("T37 create latency over %d sequential creates: p50=%.1f ms p95=%.1f ms max=%.1f ms%n",
                n, p50, p95, max);
        assertThat(p95).as("warm p95 create latency in ms").isLessThan(500.0);
    }
}
