package com.finpath.holding;

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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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

/** T65 acceptance over the scratch database under RLS (skipped without one): CRUD, classes, aggregate, consent, scoping. */
class HoldingApiDbTest {

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
        HoldingService service = new HoldingService(new HoldingRepository(() -> jdbc, () -> tx), consents);
        om = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mvc = MockMvcBuilders.standaloneSetup(new HoldingController(service))
                .setControllerAdvice(new HoldingExceptionHandler())
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

    private static final List<String> CLASSES = List.of("epf", "ppf", "nps", "mf", "stocks", "fd", "gold");

    private static String body(String cls, String name, Long invested, Long current, Long monthly) {
        StringBuilder s = new StringBuilder("{\"assetClass\":\"" + cls + "\"");
        if (name != null) {
            s.append(",\"name\":\"").append(name).append('"');
        }
        if (invested != null) {
            s.append(",\"investedPaise\":").append(invested);
        }
        if (current != null) {
            s.append(",\"currentValuePaise\":").append(current);
        }
        if (monthly != null) {
            s.append(",\"monthlyContributionPaise\":").append(monthly);
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

    private long count(UUID user) throws SQLException {
        try (Connection c = TestDb.admin(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM holding WHERE user_id = '" + user + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static JsonNode byClass(JsonNode agg, String cls) {
        for (JsonNode n : agg.get("classes")) {
            if (n.get("assetClass").asText().equals(cls)) {
                return n;
            }
        }
        throw new AssertionError("class missing from aggregate: " + cls);
    }

    @Test
    void allSevenAssetClassesAreAccepted() throws Exception {
        as(a);
        for (String cls : CLASSES) {
            JsonNode h = send(post("/holdings"), body(cls, "Label " + cls, 1_000L, 1_100L, 10L), 201);
            assertThat(h.get("assetClass").asText()).isEqualTo(cls);
        }
        assertThat(count(a)).isEqualTo(7);
    }

    @Test
    void unknownAssetClassIs400AndWritesNothing() throws Exception {
        as(a);
        for (String bad : List.of("crypto", "EPF", "", "real_estate")) {
            JsonNode err = send(post("/holdings"), body(bad, "x", 1L, 1L, 1L), 400);
            assertThat(err.get("error").asText()).isEqualTo("invalid_request");
        }
        send(post("/holdings"), "{\"name\":\"x\"}", 400);
        assertThat(count(a)).isZero();
    }

    @Test
    void nameIsALabelNotAnIdentifier() throws Exception {
        as(a);
        List<String> bad = List.of("123456789012", "1234 5678 9012", "IN-1234-5678-90", "ABCDE1234F", "1234567/89012", "",
                "x".repeat(61));
        for (String name : bad) {
            send(post("/holdings"), body("mf", name, 1L, 1L, 1L), 400);
        }
        send(post("/holdings"), "{\"assetClass\":\"mf\"}", 400);   // name required
        send(post("/holdings"), body("mf", "Fund 2024", 1L, 1L, 1L), 201);
        assertThat(count(a)).isEqualTo(1);
    }

    @Test
    void invalidAmountsAre400() throws Exception {
        as(a);
        send(post("/holdings"), body("fd", "x", -1L, 0L, 0L), 400);
        send(post("/holdings"), body("fd", "x", 0L, -1L, 0L), 400);
        send(post("/holdings"), body("fd", "x", 0L, 0L, -1L), 400);
        send(post("/holdings"), "{\"assetClass\":\"fd\",\"name\":\"x\",\"investedPaise\":\"5\"}", 400);
        send(post("/holdings"), "{\"assetClass\":\"fd\",\"name\":\"x\",\"investedPaise\":5.5}", 400);
        assertThat(count(a)).isZero();
    }

    @Test
    void crudRoundTripsAndAmountsDefaultToZero() throws Exception {
        as(a);
        JsonNode created = send(post("/holdings"), body("nps", "  Tier 1  ", null, null, null), 201);
        assertThat(created.get("name").asText()).isEqualTo("Tier 1");
        assertThat(created.get("investedPaise").asLong()).isZero();
        assertThat(created.get("currentValuePaise").asLong()).isZero();
        assertThat(created.get("monthlyContributionPaise").asLong()).isZero();
        String path = "/holdings/" + created.get("id").asText();
        assertThat(send(get(path), null, 200)).isEqualTo(created);
        send(post("/holdings"), body("epf", "Employer EPF", 5L, 6L, 7L), 201);
        assertThat(send(get("/holdings"), null, 200).findValuesAsText("assetClass")).containsExactly("epf", "nps");

        JsonNode updated = send(put(path), body("gold", "Coins", 400L, 500L, 20L), 200);
        assertThat(updated.get("assetClass").asText()).isEqualTo("gold");
        assertThat(updated.get("currentValuePaise").asLong()).isEqualTo(500L);
        send(delete(path), null, 204);
        send(get(path), null, 404);
        send(delete(path), null, 404);
    }

    @Test
    void aggregateEqualsPerClassTotalsAndGrandTotals() throws Exception {
        as(a);
        send(post("/holdings"), body("mf", "Fund one", 1_000L, 1_300L, 100L), 201);
        send(post("/holdings"), body("mf", "Fund two", 2_000L, 1_900L, 50L), 201);
        send(post("/holdings"), body("epf", "EPF", 9_000L, 9_500L, 400L), 201);
        send(post("/holdings"), body("gold", "Coins", 300L, 350L, 0L), 201);
        // another user's holdings must not leak into the sums
        as(b);
        send(post("/holdings"), body("mf", "Other", 777_777L, 777_777L, 777L), 201);
        as(a);

        JsonNode agg = send(get("/holdings/aggregate"), null, 200);
        assertThat(agg.get("classes")).hasSize(7);
        JsonNode mf = byClass(agg, "mf");
        assertThat(mf.get("investedPaise").asLong()).isEqualTo(3_000L);
        assertThat(mf.get("currentValuePaise").asLong()).isEqualTo(3_200L);
        assertThat(mf.get("monthlyContributionPaise").asLong()).isEqualTo(150L);
        assertThat(mf.get("count").asLong()).isEqualTo(2);
        assertThat(byClass(agg, "epf").get("currentValuePaise").asLong()).isEqualTo(9_500L);
        assertThat(byClass(agg, "stocks").get("count").asLong()).isZero();

        long inv = 0;
        long cur = 0;
        long mon = 0;
        long cnt = 0;
        for (JsonNode n : agg.get("classes")) {
            inv += n.get("investedPaise").asLong();
            cur += n.get("currentValuePaise").asLong();
            mon += n.get("monthlyContributionPaise").asLong();
            cnt += n.get("count").asLong();
        }
        JsonNode total = agg.get("total");
        assertThat(total.get("investedPaise").asLong()).isEqualTo(inv).isEqualTo(12_300L);
        assertThat(total.get("currentValuePaise").asLong()).isEqualTo(cur).isEqualTo(13_050L);
        assertThat(total.get("monthlyContributionPaise").asLong()).isEqualTo(mon).isEqualTo(550L);
        assertThat(total.get("count").asLong()).isEqualTo(cnt).isEqualTo(4);
    }

    @Test
    void aggregateOfNobodyIsAllZeros() throws Exception {
        as(a);
        JsonNode agg = send(get("/holdings/aggregate"), null, 200);
        assertThat(agg.get("classes")).hasSize(7);
        assertThat(agg.get("total").get("count").asLong()).isZero();
    }

    @Test
    void databaseChecksRejectABadRowEvenViaDirectSql() throws Exception {
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO holding (user_id, asset_class, name) VALUES (?, 'crypto', 'x')")) {
            ps.setObject(1, a);
            assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class).hasMessageContaining("asset_class");
        }
    }

    @Test
    void anotherUsersHoldingIs404ForReadUpdateAndDelete() throws Exception {
        as(a);
        JsonNode h = send(post("/holdings"), body("ppf", "PPF", 100L, 110L, 5L), 201);
        String path = "/holdings/" + h.get("id").asText();
        as(b);
        send(get(path), null, 404);
        send(put(path), body("ppf", "Hijack", 1L, 1L, 1L), 404);
        send(delete(path), null, 404);
        assertThat(send(get("/holdings"), null, 200)).isEmpty();
        as(a);
        assertThat(send(get(path), null, 200).get("name").asText()).isEqualTo("PPF");
    }

    @Test
    void aBodyUserIdIsIgnoredAndTheOwnerIsThePrincipal() throws Exception {
        as(a);
        String json = body("fd", "FD", 1L, 1L, 1L).replaceFirst("\\{", "{\"userId\":\"" + b + "\",");
        send(post("/holdings"), json, 201);
        assertThat(count(a)).isEqualTo(1);
        assertThat(count(b)).isZero();
    }

    @Test
    void writesNeedConsent() throws Exception {
        UUID noConsent = newUser(null, false);
        as(noConsent);
        assertThat(send(post("/holdings"), body("fd", "FD", 1L, 1L, 1L), 403).get("error").asText())
                .isEqualTo("consent_required");
        assertThat(count(noConsent)).isZero();
    }
}
