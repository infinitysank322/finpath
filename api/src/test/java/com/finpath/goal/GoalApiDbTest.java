package com.finpath.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.finpath.auth.CurrentUser;
import com.finpath.auth.CurrentUserIdArgumentResolver;
import com.finpath.db.TestDb;
import com.finpath.dpdp.ConsentService;
import com.finpath.dpdp.ConsentStatement;
import com.finpath.rls.RlsTransactionManager;
import com.finpath.rules.DatedGoal;
import com.finpath.rules.Money;
import com.finpath.rules.RuleConfig;
import com.finpath.rules.golden.GoldenAssert;
import com.finpath.rules.golden.GoldenFixture;
import com.finpath.rules.golden.GoldenKeys;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/** T61 acceptance over the scratch database under RLS (skipped without one): CRUD, status, consent, scoping. */
class GoalApiDbTest {

    private static final GoldenFixture FX = GoldenFixture.shared();
    /** Same pin and cycle start day as RulesGoldenTest.datedGoalMonthlyNeed. */
    private static final LocalDate REF = LocalDate.of(2026, 10, 25);
    private static final int START_DAY = 25;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private MockMvc mvc;
    private ObjectMapper om;
    private UUID a;
    private UUID b;

    @BeforeEach
    void setUp() throws Exception {
        TestDb.requireAndMigrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(TestDb.URL, TestDb.USER, TestDb.PASSWORD);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new RlsTransactionManager(ds, CurrentUser::id));
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("jdbc", jdbc);
        bf.registerSingleton("tx", tx);
        ConsentService consents = new ConsentService(bf.getBeanProvider(JdbcTemplate.class),
                bf.getBeanProvider(TransactionTemplate.class));
        GoalService service = new GoalService(new GoalRepository(() -> jdbc, () -> tx), consents,
                RuleConfig.defaults(), Clock.fixed(REF.atStartOfDay(IST).toInstant(), IST));
        om = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mvc = MockMvcBuilders.standaloneSetup(new GoalController(service, null))
                .setControllerAdvice(new GoalExceptionHandler())
                .setCustomArgumentResolvers(new CurrentUserIdArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om)).build();
        a = newUser(consents, true);
        b = newUser(consents, true);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static UUID newUser(ConsentService consents, boolean consent) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, name, email, cycle_start_day) VALUES (?, 'T', ?, " + START_DAY + ")")) {
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

    private static String body(String type, Long target, LocalDate due, Long current, Long priority) {
        StringBuilder s = new StringBuilder("{\"type\":\"" + type + "\"");
        if (target != null) {
            s.append(",\"targetAmountPaise\":").append(target);
        }
        if (due != null) {
            s.append(",\"targetDate\":\"").append(due).append('"');
        }
        if (current != null) {
            s.append(",\"currentAmountPaise\":").append(current);
        }
        if (priority != null) {
            s.append(",\"priority\":").append(priority);
        }
        return s.append('}').toString();
    }

    private JsonNode send(MockHttpServletRequestBuilder req, String json, int expected) throws Exception {
        if (json != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        String out = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return out.isEmpty() ? null : om.readTree(out);
    }

    private long goalCount(UUID user) throws SQLException {
        try (Connection c = TestDb.admin(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM goal WHERE user_id = '" + user + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void fixtureGoalReturnsTheRulesMonthlyNeedAndTracksAgainstThePlannedAmount() throws Exception {
        long target = FX.paise(GoldenKeys.GOAL_TARGET);
        int cycles = FX.integer(GoldenKeys.GOAL_CYCLES);
        LocalDate due = REF.plusMonths(cycles);
        as(a);
        JsonNode g = send(post("/goals"), body("marriage", target, due, 0L, 1L), 201);
        String url = "/goals/" + g.get("id").asText() + "/status";

        DatedGoal.Result expected = DatedGoal.evaluate(Money.ofPaise(target), Money.ZERO, Money.ZERO, REF, due,
                START_DAY, RuleConfig.defaults());
        JsonNode behind = send(get(url).param("plannedMonthlyPaise", "0"), null, 200);
        assertThat(behind.get("cyclesRemaining").asInt()).isEqualTo(cycles).isEqualTo(expected.cyclesRemaining());
        assertThat(behind.get("monthlyNeedPaise").asLong()).isEqualTo(expected.monthlyNeed().paise());
        GoldenAssert.assertWithinOnePercent(FX.paise(GoldenKeys.GOAL_MONTHLY), behind.get("monthlyNeedPaise").asLong());
        assertThat(behind.get("shortfallPaise").asLong()).isEqualTo(target);
        assertThat(behind.get("status").asText()).isEqualTo("BEHIND");
        assertThat(behind.get("onTrack").asBoolean()).isFalse();

        long need = expected.monthlyNeed().paise();
        JsonNode exact = send(get(url).param("plannedMonthlyPaise", Long.toString(need)), null, 200);
        assertThat(exact.get("status").asText()).isEqualTo("ON_TRACK");
        assertThat(exact.get("onTrack").asBoolean()).isTrue();
        JsonNode oneShort = send(get(url).param("plannedMonthlyPaise", Long.toString(need - 1)), null, 200);
        assertThat(oneShort.get("onTrack").asBoolean()).isFalse();
    }

    @Test
    void subThreeYearHorizonIsSafeOnlyAndThreeYearsPermitsEquity() throws Exception {
        as(a);
        JsonNode shortGoal = send(post("/goals"), body("other", 3_000_000L, REF.plusMonths(34), null, null), 201);
        JsonNode longGoal = send(post("/goals"), body("other", 3_000_000L, REF.plusMonths(37), null, null), 201);
        assertThat(send(get("/goals/" + shortGoal.get("id").asText() + "/status").param("plannedMonthlyPaise", "1"),
                null, 200).get("instrumentClass").asText()).isEqualTo("SAFE_ONLY");
        assertThat(send(get("/goals/" + longGoal.get("id").asText() + "/status").param("plannedMonthlyPaise", "1"),
                null, 200).get("instrumentClass").asText()).isEqualTo("EQUITY_ALLOWED");
    }

    @Test
    void achievedAndPastDueGoalsReportTheirStatus() throws Exception {
        as(a);
        JsonNode done = send(post("/goals"), body("other", 1_000_000L, REF.plusMonths(6), 1_000_000L, null), 201);
        JsonNode late = send(post("/goals"), body("other", 1_000_000L, REF.minusMonths(6), 10L, null), 201);
        JsonNode d = send(get("/goals/" + done.get("id").asText() + "/status").param("plannedMonthlyPaise", "0"),
                null, 200);
        assertThat(d.get("status").asText()).isEqualTo("ACHIEVED");
        assertThat(d.get("onTrack").asBoolean()).isTrue();
        JsonNode l = send(get("/goals/" + late.get("id").asText() + "/status").param("plannedMonthlyPaise", "0"),
                null, 200);
        assertThat(l.get("status").asText()).isEqualTo("PAST_DUE");
        assertThat(l.get("onTrack").asBoolean()).isFalse();
    }

    @Test
    void statusNeedsANonNegativePlannedAmount() throws Exception {
        as(a);
        JsonNode g = send(post("/goals"), body("other", 1_000_000L, REF.plusMonths(12), null, null), 201);
        String url = "/goals/" + g.get("id").asText() + "/status";
        send(get(url), null, 400);
        send(get(url).param("plannedMonthlyPaise", "-1"), null, 400);
    }

    @Test
    void crudRoundTripsAndDefaultsApply() throws Exception {
        as(a);
        LocalDate due = REF.plusMonths(20);
        JsonNode created = send(post("/goals"), body("education", 4_000_000L, due, null, null), 201);
        assertThat(created.get("currentAmountPaise").asLong()).isZero();
        assertThat(created.get("priority").asInt()).isEqualTo(5);
        assertThat(created.get("targetDate").asText()).isEqualTo(due.toString());
        assertThat(send(get("/goals/" + created.get("id").asText()), null, 200)).isEqualTo(created);
        send(post("/goals"), body("home", 5_000_000L, due, 100L, 1L), 201);
        JsonNode list = send(get("/goals"), null, 200);
        assertThat(list.findValuesAsText("type")).containsExactly("home", "education");   // priority 1 first

        JsonNode updated = send(put("/goals/" + created.get("id").asText()),
                body("education", 4_500_000L, due, 250_000L, 2L), 200);
        assertThat(updated.get("targetAmountPaise").asLong()).isEqualTo(4_500_000L);
        assertThat(updated.get("currentAmountPaise").asLong()).isEqualTo(250_000L);
        assertThat(updated.get("priority").asInt()).isEqualTo(2);
        send(delete("/goals/" + created.get("id").asText()), null, 204);
        send(get("/goals/" + created.get("id").asText()), null, 404);
    }

    @Test
    void invalidInputsAre400AndWriteNothing() throws Exception {
        as(a);
        LocalDate due = REF.plusMonths(12);
        List<String> bad = List.of(
                body("holiday", 1000L, due, null, null),            // unknown type
                body("other", 0L, due, null, null),                 // zero target
                body("other", -5L, due, null, null),
                body("other", null, due, null, null),               // no target
                body("other", 1000L, null, null, null),             // no date
                body("other", 1000L, due, -1L, null),               // negative current
                body("other", 1000L, due, null, 0L),                // priority below range
                body("other", 1000L, due, null, 11L),
                "{\"type\":\"other\",\"targetAmountPaise\":\"1000\",\"targetDate\":\"" + due + "\"}",
                "{\"type\":\"other\",\"targetAmountPaise\":1000.5,\"targetDate\":\"" + due + "\"}");
        for (String json : bad) {
            // malformed JSON types are rejected by the framework's handler in the real app (empty body here)
            JsonNode err = send(post("/goals"), json, 400);
            if (err != null) {
                assertThat(err.get("error").asText()).isEqualTo("invalid_request");
            }
        }
        assertThat(goalCount(a)).isZero();
    }

    @Test
    void databaseChecksRejectABadRowEvenViaDirectSql() throws Exception {
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO goal (user_id, type, target_amount_paise, target_date, priority) "
                        + "VALUES (?, 'other', 1000, DATE '2030-01-01', 99)")) {
            ps.setObject(1, a);
            assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class).hasMessageContaining("priority");
        }
    }

    @Test
    void anotherUsersGoalIs404ForReadStatusUpdateAndDelete() throws Exception {
        as(a);
        JsonNode g = send(post("/goals"), body("other", 1_000_000L, REF.plusMonths(12), null, null), 201);
        String path = "/goals/" + g.get("id").asText();
        as(b);
        send(get(path), null, 404);
        send(get(path + "/status").param("plannedMonthlyPaise", "1"), null, 404);
        send(put(path), body("other", 9L, REF.plusMonths(1), null, null), 404);
        send(delete(path), null, 404);
        assertThat(send(get("/goals"), null, 200)).isEmpty();
        as(a);
        assertThat(send(get(path), null, 200).get("targetAmountPaise").asLong()).isEqualTo(1_000_000L);
    }

    @Test
    void aBodyUserIdIsIgnoredAndTheOwnerIsThePrincipal() throws Exception {
        as(a);
        String json = body("other", 1_000_000L, REF.plusMonths(12), null, null)
                .replaceFirst("\\{", "{\"userId\":\"" + b + "\",");
        send(post("/goals"), json, 201);
        assertThat(goalCount(a)).isEqualTo(1);
        assertThat(goalCount(b)).isZero();
    }

    @Test
    void writesNeedConsent() throws Exception {
        UUID noConsent = newUser(null, false);
        as(noConsent);
        assertThat(send(post("/goals"), body("other", 1_000_000L, REF.plusMonths(12), null, null), 403)
                .get("error").asText()).isEqualTo("consent_required");
        assertThat(goalCount(noConsent)).isZero();
    }
}
