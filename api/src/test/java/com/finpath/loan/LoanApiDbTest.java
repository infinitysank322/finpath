package com.finpath.loan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import com.finpath.rules.AmortisingLoan;
import com.finpath.rules.BulletLoan;
import com.finpath.rules.Money;
import com.finpath.rules.golden.GoldenFixture;
import com.finpath.rules.golden.GoldenKeys;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
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
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/** T48 acceptance over the scratch database under RLS (skipped without one): CRUD, type rules, stated EMI wins. */
class LoanApiDbTest {

    private static final GoldenFixture FX = GoldenFixture.shared();
    private static final long HOME_STATED = FX.paise(GoldenKeys.LOAN_HOME_EMI_STATED);
    private static final long HOME_PRINCIPAL = FX.paise(GoldenKeys.LOAN_HOME_PRINCIPAL);
    private static final BigDecimal HOME_RATE = FX.decimal(GoldenKeys.LOAN_HOME_RATE);
    private static final int HOME_LEFT = FX.integer(GoldenKeys.LOAN_HOME_EMIS_LEFT);
    private static final long GOLD_PRINCIPAL = FX.paise(GoldenKeys.LOAN_GOLD_PRINCIPAL);
    private static final BigDecimal GOLD_RATE = FX.decimal(GoldenKeys.LOAN_GOLD_RATE);
    private static final String DUE = "2031-10-15";

    private MockMvc mvc;
    private ObjectMapper om;
    private LoanService service;
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
        service = new LoanService(new LoanRepository(() -> jdbc, () -> tx), consents);
        om = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mvc = MockMvcBuilders.standaloneSetup(new LoanController(service))
                .setControllerAdvice(new LoanExceptionHandler())
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
                "INSERT INTO users (id, name, email, cycle_start_day) VALUES (?, 'T', ?, 1)")) {
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

    private static String body(String lender, String type, long principal, BigDecimal rate, Long emi, Integer left,
            String due, Long fee) {
        StringBuilder s = new StringBuilder("{\"lender\":\"" + lender + "\",\"type\":\"" + type
                + "\",\"principalOutstandingPaise\":" + principal + ",\"rate\":\"" + rate.toPlainString() + "\"");
        if (emi != null) {
            s.append(",\"emiPaise\":").append(emi);
        }
        if (left != null) {
            s.append(",\"emisLeft\":").append(left);
        }
        if (due != null) {
            s.append(",\"bulletDueDate\":\"").append(due).append('"');
        }
        if (fee != null) {
            s.append(",\"upfrontFeePaise\":").append(fee);
        }
        return s.append('}').toString();
    }

    private JsonNode send(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, String json,
            int expected) throws Exception {
        if (json != null) {
            req = req.contentType(MediaType.APPLICATION_JSON).content(json);
        }
        String out = mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString();
        return out.isEmpty() ? null : om.readTree(out);
    }

    private long loanCount(UUID user) throws SQLException {
        try (Connection c = TestDb.admin(); Statement s = c.createStatement();
                ResultSet rs = s.executeQuery("SELECT count(*) FROM loan WHERE user_id = '" + user + "'")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void statedHomeEmiIsStoredVerbatimEvenThoughTheComputedEmiDiffers() throws Exception {
        long computed = AmortisingLoan.emi(Money.ofPaise(HOME_PRINCIPAL), HOME_RATE, HOME_LEFT).paise();
        assertThat(computed).as("fixture must make stated and computed EMI differ").isNotEqualTo(HOME_STATED);
        as(a);
        JsonNode v = send(post("/loans"), body("Home Bank", "emi", HOME_PRINCIPAL, HOME_RATE, HOME_STATED, HOME_LEFT,
                null, null), 201);
        assertThat(v.get("emiPaise").asLong()).isEqualTo(HOME_STATED);
        assertThat(v.get("emiDerived").asBoolean()).isFalse();
        JsonNode read = send(get("/loans/" + v.get("id").asText()), null, 200);
        assertThat(read.get("emiPaise").asLong()).isEqualTo(HOME_STATED);
        assertThat(read.get("emiDerived").asBoolean()).isFalse();
        assertThat(new BigDecimal(read.get("rate").asText())).isEqualByComparingTo(HOME_RATE);
    }

    @Test
    void omittedEmiIsDerivedByTheRulesAndFlagged() throws Exception {
        as(a);
        JsonNode emi = send(post("/loans"), body("Home Bank", "emi", HOME_PRINCIPAL, HOME_RATE, null, HOME_LEFT, null,
                null), 201);
        assertThat(emi.get("emiPaise").asLong())
                .isEqualTo(AmortisingLoan.emi(Money.ofPaise(HOME_PRINCIPAL), HOME_RATE, HOME_LEFT).paise());
        assertThat(emi.get("emiDerived").asBoolean()).isTrue();
        JsonNode bullet = send(post("/loans"), body("Gold Lender", "bullet", GOLD_PRINCIPAL, GOLD_RATE, null, null, DUE,
                null), 201);
        assertThat(bullet.get("emiPaise").asLong())
                .isEqualTo(BulletLoan.monthlyInterest(Money.ofPaise(GOLD_PRINCIPAL), GOLD_RATE).paise());
        assertThat(bullet.get("emiPaise").asLong()).isEqualTo(FX.paise(GoldenKeys.LOAN_GOLD_MONTHLY_INTEREST));
        assertThat(bullet.get("emiDerived").asBoolean()).isTrue();
    }

    @Test
    void allThreeTypesRoundTripWithIdenticalFields() throws Exception {
        as(a);
        long fee = 1L;
        JsonNode e = send(post("/loans"), body("Home Bank", "emi", HOME_PRINCIPAL, HOME_RATE, HOME_STATED, HOME_LEFT,
                null, null), 201);
        JsonNode bu = send(post("/loans"), body("Gold Lender", "bullet", GOLD_PRINCIPAL, GOLD_RATE, FX.paise(GoldenKeys.LOAN_GOLD_MONTHLY_INTEREST), null,
                DUE, null), 201);
        JsonNode c = send(post("/loans"), body("Card Bank", "card_emi", 5000000L, new BigDecimal("0"), 850000L, 6,
                null, fee), 201);
        JsonNode list = send(get("/loans"), null, 200);
        assertThat(list).hasSize(3);
        assertThat(list.findValuesAsText("type")).containsExactly("bullet", "card_emi", "emi");
        for (JsonNode created : List.of(e, bu, c)) {
            JsonNode read = send(get("/loans/" + created.get("id").asText()), null, 200);
            assertThat(read).isEqualTo(created);
        }
        assertThat(bu.get("bulletDueDate").asText()).isEqualTo(DUE);
        assertThat(bu.get("emisLeft").isNull()).isTrue();
        assertThat(c.get("upfrontFeePaise").asLong()).isEqualTo(fee);
        assertThat(c.get("emisLeft").asInt()).isEqualTo(6);
        assertThat(e.get("createdAt").asText()).isNotBlank();
    }

    @Test
    void bulletWithoutDueDateIs400AndWritesNothing() throws Exception {
        as(a);
        JsonNode err = send(post("/loans"), body("Gold Lender", "bullet", GOLD_PRINCIPAL, GOLD_RATE, null, null, null,
                null), 400);
        assertThat(err.get("error").asText()).isEqualTo("invalid_request");
        assertThat(loanCount(a)).isZero();
    }

    @Test
    void databaseChecksRejectABulletWithoutDateEvenViaDirectSql() throws Exception {
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO loan (user_id, lender, type, principal_outstanding_paise, rate, emi_paise) "
                        + "VALUES (?, 'X', 'bullet', 1000, 0.1, 10)")) {
            ps.setObject(1, a);
            assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class)
                    .hasMessageContaining("loan_bullet_due");
        }
    }

    @Test
    void invalidInputsAreAll400AndWriteNothing() throws Exception {
        as(a);
        String longName = "x".repeat(61);
        List<String> bad = List.of(
                body("Bank", "personal", 1000L, HOME_RATE, 10L, 12, null, null),                 // unknown type
                body("   ", "emi", HOME_PRINCIPAL, HOME_RATE, HOME_STATED, HOME_LEFT, null, null),
                body(longName, "emi", HOME_PRINCIPAL, HOME_RATE, HOME_STATED, HOME_LEFT, null, null),
                body("Bank", "emi", HOME_PRINCIPAL, HOME_RATE, HOME_STATED, HOME_LEFT, DUE, null),   // date on emi
                body("Bank", "emi", HOME_PRINCIPAL, HOME_RATE, HOME_STATED, null, null, null),        // no tenure
                body("Bank", "emi", HOME_PRINCIPAL, HOME_RATE, HOME_STATED, 0, null, null),
                body("Bank", "bullet", GOLD_PRINCIPAL, GOLD_RATE, 10L, 12, DUE, null),                 // tenure on bullet
                body("Bank", "card_emi", 5000000L, HOME_RATE, null, 6, null, null),                    // no stated emi
                body("Bank", "emi", HOME_PRINCIPAL, HOME_RATE, 0L, HOME_LEFT, null, null),
                body("Bank", "emi", HOME_PRINCIPAL, HOME_RATE, HOME_STATED, HOME_LEFT, null, 5L),     // fee on non-card
                body("Bank", "card_emi", 5000000L, HOME_RATE, 850000L, 6, null, 5000000L),            // fee >= principal
                body("Bank", "emi", HOME_PRINCIPAL, new BigDecimal("1"), HOME_STATED, HOME_LEFT, null, null),
                body("Bank", "emi", HOME_PRINCIPAL, new BigDecimal("-0.1"), HOME_STATED, HOME_LEFT, null, null),
                body("Bank", "emi", HOME_PRINCIPAL, new BigDecimal("0.12345678901"), HOME_STATED, HOME_LEFT, null,
                        null),
                body("Bank", "emi", -1L, HOME_RATE, HOME_STATED, HOME_LEFT, null, null),
                "{\"lender\":\"Bank\",\"type\":\"emi\",\"principalOutstandingPaise\":\"1000\",\"rate\":\"0.1\","
                        + "\"emiPaise\":10,\"emisLeft\":12}",                                          // string amount
                "{\"lender\":\"Bank\",\"type\":\"emi\",\"principalOutstandingPaise\":1000.5,\"rate\":\"0.1\","
                        + "\"emiPaise\":10,\"emisLeft\":12}");                                         // decimal amount
        for (String json : bad) {
            JsonNode err = send(post("/loans"), json, 400);   // malformed JSON types are rejected by the framework's handler in the real app
            if (err != null) {
                assertThat(err.get("error").asText()).isEqualTo("invalid_request");
            }
        }
        assertThat(loanCount(a)).isZero();
    }

    @Test
    void putIsAFullReplaceThatClearsOrSetsTheDerivedFlag() throws Exception {
        as(a);
        JsonNode derived = send(post("/loans"), body("Home Bank", "emi", HOME_PRINCIPAL, HOME_RATE, null, HOME_LEFT,
                null, null), 201);
        String path = "/loans/" + derived.get("id").asText();
        assertThat(derived.get("emiDerived").asBoolean()).isTrue();
        JsonNode stated = send(put(path), body("Home Bank", "emi", HOME_PRINCIPAL, HOME_RATE, HOME_STATED, HOME_LEFT,
                null, null), 200);
        assertThat(stated.get("emiPaise").asLong()).isEqualTo(HOME_STATED);
        assertThat(stated.get("emiDerived").asBoolean()).isFalse();
        long newPrincipal = HOME_PRINCIPAL / 2;
        JsonNode again = send(put(path), body("Home Bank", "emi", newPrincipal, HOME_RATE, null, HOME_LEFT, null, null),
                200);
        assertThat(again.get("emiDerived").asBoolean()).isTrue();
        assertThat(again.get("emiPaise").asLong())
                .isEqualTo(AmortisingLoan.emi(Money.ofPaise(newPrincipal), HOME_RATE, HOME_LEFT).paise());
        assertThat(again.get("id")).isEqualTo(derived.get("id"));
    }

    @Test
    void anotherUsersLoanIs404ForReadReplaceAndDelete() throws Exception {
        as(a);
        JsonNode mine = send(post("/loans"), body("Home Bank", "emi", HOME_PRINCIPAL, HOME_RATE, HOME_STATED,
                HOME_LEFT, null, null), 201);
        String path = "/loans/" + mine.get("id").asText();
        as(b);
        assertThat(send(get(path), null, 404).get("error").asText()).isEqualTo("not_found");
        send(put(path), body("Taken", "emi", 1000L, HOME_RATE, 10L, 12, null, null), 404);
        send(delete(path), null, 404);
        assertThat(send(get("/loans"), null, 200)).isEmpty();
        as(a);
        assertThat(send(get(path), null, 200).get("lender").asText()).isEqualTo("Home Bank");
    }

    @Test
    void aBodyUserIdIsIgnoredAndTheOwnerIsThePrincipal() throws Exception {
        as(a);
        String json = body("Home Bank", "emi", HOME_PRINCIPAL, HOME_RATE, HOME_STATED, HOME_LEFT, null, null)
                .replaceFirst("\\{", "{\"userId\":\"" + b + "\",");
        send(post("/loans"), json, 201);
        assertThat(loanCount(a)).isEqualTo(1);
        assertThat(loanCount(b)).isZero();
    }

    @Test
    void writesNeedConsentAndDeleteRemovesTheRow() throws Exception {
        UUID noConsent = UUID.randomUUID();
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users (id, name, email, cycle_start_day) VALUES (?, 'T', ?, 1)")) {
            ps.setObject(1, noConsent);
            ps.setString(2, noConsent + "@example.test");
            ps.executeUpdate();
        }
        as(noConsent);
        assertThat(send(post("/loans"), body("Home Bank", "emi", HOME_PRINCIPAL, HOME_RATE, HOME_STATED, HOME_LEFT,
                null, null), 403).get("error").asText()).isEqualTo("consent_required");
        assertThat(loanCount(noConsent)).isZero();

        as(a);
        JsonNode v = send(post("/loans"), body("Home Bank", "emi", HOME_PRINCIPAL, HOME_RATE, HOME_STATED, HOME_LEFT,
                null, null), 201);
        send(delete("/loans/" + v.get("id").asText()), null, 204);
        assertThat(loanCount(a)).isZero();
    }

    @Test
    void loanServiceDoesNoBigDecimalArithmetic() throws Exception {
        Path src = Path.of("src/main/java/com/finpath/loan/LoanService.java");
        if (!Files.exists(src)) {
            src = Path.of("api").resolve(src);
        }
        String code = Files.readString(src);
        assertThat(code).doesNotContain(".multiply(", ".divide(", ".add(", ".subtract(", ".pow(", ".setScale(");
    }
}
