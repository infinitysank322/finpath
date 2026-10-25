package com.finpath.checklist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import com.finpath.loan.LoanRepository;
import com.finpath.plan.PlanCycleService;
import com.finpath.rls.RlsTransactionManager;
import com.finpath.rules.Statistics;
import com.finpath.rules.golden.GoldenFixture;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * T71 acceptance over the scratch database under RLS (skipped without one). Amounts come from the
 * committed sample fixture (persona.sample.properties), never from literals here.
 */
class ChecklistApiDbTest {

    private static final GoldenFixture FX = GoldenFixture.shared();

    private MockMvc mvc;
    private ObjectMapper om;
    private UUID a;
    private UUID b;
    private UUID homeLoan;
    private UUID goldLoan;
    private UUID goal;
    private JdbcTemplate jdbcRef;
    private TransactionTemplate txRef;

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
        om = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        PlanCycleService cycles = new PlanCycleService(bf.getBeanProvider(JdbcTemplate.class),
                bf.getBeanProvider(TransactionTemplate.class), consents, om);
        jdbcRef = jdbc;
        txRef = tx;
        ChecklistService service = new ChecklistService(() -> jdbc, () -> tx, consents, cycles);
        mvc = MockMvcBuilders.standaloneSetup(new ChecklistController(service))
                .setControllerAdvice(new ChecklistExceptionHandler())
                .setCustomArgumentResolvers(new CurrentUserIdArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om)).build();
        a = newUser(consents, true);
        b = newUser(consents, true);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static UUID newUser(ConsentService consents, boolean consent) throws Exception {
        UUID id = UUID.randomUUID();
        admin("INSERT INTO users (id, name, email, cycle_start_day) VALUES ('" + id + "', 'T', '" + id
                + "@example.test', 1)");
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

    private static void admin(String sql) throws Exception {
        try (Connection c = TestDb.admin(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String scalar(String sql) throws Exception {
        try (Connection c = TestDb.admin(); PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getString(1);
        }
    }

    private JsonNode json(MvcResult r) throws Exception {
        return om.readTree(r.getResponse().getContentAsString());
    }

    /** Persona-shaped data for user a: home loan, bullet gold loan, one goal; plan with all three line kinds. */
    private void seedPersona() throws Exception {
        homeLoan = UUID.randomUUID();
        goldLoan = UUID.randomUUID();
        goal = UUID.randomUUID();
        admin("INSERT INTO loan (id, user_id, lender, type, principal_outstanding_paise, rate, emi_paise, emis_left) "
                + "VALUES ('" + homeLoan + "', '" + a + "', 'Home bank', 'emi', "
                + FX.paise("loan.home_principal_paise") + ", " + FX.decimal("loan.home_rate") + ", "
                + FX.paise("loan.home_emi_stated_paise") + ", " + FX.integer("loan.home_emis_left") + ")");
        admin("INSERT INTO loan (id, user_id, lender, type, principal_outstanding_paise, rate, emi_paise, "
                + "bullet_due_date) VALUES ('" + goldLoan + "', '" + a + "', 'Gold lender', 'bullet', "
                + FX.paise("loan.gold_principal_paise") + ", " + FX.decimal("loan.gold_rate") + ", "
                + FX.paise("loan.gold_monthly_interest_paise") + ", DATE '2031-10-01')");
        admin("INSERT INTO goal (id, user_id, type, target_amount_paise, target_date) VALUES ('" + goal + "', '"
                + a + "', 'marriage', " + FX.paise("goal.target_paise") + ", DATE '2031-01-01')");
        as(a);
        // Materialise the current cycle, then store the allocation lines the T64 run would have written.
        mvcGet("/checklist");
        admin("UPDATE plan_cycle SET allocations = '[{\"step\":\"SAFE_GOALS\",\"ref\":\"" + goal
                + "\",\"amountPaise\":" + FX.paise("goal.monthly_paise") + "},{\"step\":\"HABIT_SIP\","
                + "\"ref\":null,\"amountPaise\":" + FX.paise("rolloff.step1_paise") + "},{\"step\":\"LONG_TERM_SIP\","
                + "\"ref\":null,\"amountPaise\":" + FX.paise("rolloff.step2_paise") + "}]'::jsonb "
                + "WHERE user_id = '" + a + "' AND status = 'OPEN'");
    }

    private JsonNode mvcGet(String path) throws Exception {
        return json(mvc.perform(get(path)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode generate() throws Exception {
        return json(mvc.perform(post("/checklist/generate")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn());
    }

    private static JsonNode item(JsonNode view, String type, String ref) {
        for (JsonNode i : view.get("items")) {
            if (i.get("type").asText().equals(type) && i.get("sourceRef").asText().equals(ref)) {
                return i;
            }
        }
        throw new AssertionError("no item " + type + " " + ref);
    }

    @Test
    void generationProducesOneItemPerEmiGoalTransferAndSipWithSourceAmounts() throws Exception {
        seedPersona();
        JsonNode v = generate();
        assertThat(v.get("totalCount").asInt()).isEqualTo(5);
        assertThat(v.get("doneCount").asInt()).isZero();
        assertThat(v.get("items")).hasSize(5);
        assertThat(item(v, "EMI", homeLoan.toString()).get("amountPaise").asLong())
                .isEqualTo(FX.paise("loan.home_emi_stated_paise"));
        assertThat(item(v, "EMI", homeLoan.toString()).get("label").asText()).isEqualTo("Home bank");
        assertThat(item(v, "EMI", goldLoan.toString()).get("amountPaise").asLong())
                .isEqualTo(FX.paise("loan.gold_monthly_interest_paise"));
        assertThat(item(v, "GOAL_TRANSFER", goal.toString()).get("amountPaise").asLong())
                .isEqualTo(FX.paise("goal.monthly_paise"));
        assertThat(item(v, "SIP", "HABIT_SIP").get("amountPaise").asLong()).isEqualTo(FX.paise("rolloff.step1_paise"));
        assertThat(item(v, "SIP", "LONG_TERM_SIP").get("amountPaise").asLong())
                .isEqualTo(FX.paise("rolloff.step2_paise"));
    }

    @Test
    void generatingTwiceAddsNothingAndKeepsFrozenAmounts() throws Exception {
        seedPersona();
        generate();
        admin("UPDATE loan SET emi_paise = emi_paise + 1 WHERE id = '" + homeLoan + "'");
        JsonNode v = generate();
        assertThat(v.get("totalCount").asInt()).isEqualTo(5);
        assertThat(item(v, "EMI", homeLoan.toString()).get("amountPaise").asLong())
                .isEqualTo(FX.paise("loan.home_emi_stated_paise"));
        assertThat(scalar("SELECT count(*) FROM checklist_item WHERE user_id = '" + a + "'")).isEqualTo("5");
    }

    @Test
    void withoutAllocationsOnlyEmiItemsAreProducedAndALaterRunAddsTheRest() throws Exception {
        seedPersona();
        admin("UPDATE plan_cycle SET allocations = '[]'::jsonb WHERE user_id = '" + a + "'");
        assertThat(generate().get("totalCount").asInt()).isEqualTo(2);
        admin("UPDATE plan_cycle SET allocations = '[{\"step\":\"HABIT_SIP\",\"ref\":null,\"amountPaise\":"
                + FX.paise("rolloff.step1_paise") + "}]'::jsonb WHERE user_id = '" + a + "' AND status = 'OPEN'");
        assertThat(generate().get("totalCount").asInt()).isEqualTo(3);
    }

    @Test
    void markingDoneTwiceIsIdempotentAndKeepsTheFirstTimestamp() throws Exception {
        seedPersona();
        String id = item(generate(), "SIP", "HABIT_SIP").get("id").asText();
        JsonNode first = json(mvc.perform(post("/checklist/items/" + id + "/done")).andExpect(status().isOk())
                .andReturn());
        assertThat(first.get("done").asBoolean()).isTrue();
        String firstAt = first.get("doneAt").asText();
        String stored = scalar("SELECT done_at::text FROM checklist_item WHERE id = '" + id + "'");
        JsonNode second = json(mvc.perform(post("/checklist/items/" + id + "/done")).andExpect(status().isOk())
                .andReturn());
        assertThat(second.get("doneAt").asText()).isEqualTo(firstAt);
        assertThat(scalar("SELECT done_at::text FROM checklist_item WHERE id = '" + id + "'")).isEqualTo(stored);
        JsonNode list = mvcGet("/checklist");
        assertThat(list.get("doneCount").asInt()).isEqualTo(1);
        assertThat(list.get("totalCount").asInt()).isEqualTo(5);
    }

    @Test
    void anotherUsersItemIsNotFoundAndStaysUntouched() throws Exception {
        seedPersona();
        String id = item(generate(), "SIP", "HABIT_SIP").get("id").asText();
        as(b);
        mvc.perform(post("/checklist/items/" + id + "/done")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mvc.perform(post("/checklist/items/" + UUID.randomUUID() + "/done")).andExpect(status().isNotFound());
        assertThat(scalar("SELECT count(*) FROM checklist_item WHERE done_at IS NOT NULL")).isEqualTo("0");
        assertThat(mvcGet("/checklist").get("totalCount").asInt()).isZero();
    }

    @Test
    void withoutConsentEveryEndpointIs403() throws Exception {
        UUID c = newUser(null, false);
        as(c);
        mvc.perform(post("/checklist/generate")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("consent_required"));
        mvc.perform(get("/checklist")).andExpect(status().isForbidden());
        mvc.perform(get("/checklist/streak")).andExpect(status().isForbidden());
        mvc.perform(post("/checklist/items/" + UUID.randomUUID() + "/done")).andExpect(status().isForbidden());
    }

    @Test
    void unknownPastCycleIsNotFound() throws Exception {
        seedPersona();
        mvc.perform(get("/checklist").param("cycleStart", "2001-01-01")).andExpect(status().isNotFound());
    }

    /** Cycle start {@code n} months before the current one (the tests use start day 1). */
    private static LocalDate ago(int n) {
        return LocalDate.now().withDayOfMonth(1).minusMonths(n);
    }

    private void closedCycle(UUID user, LocalDate start, boolean... itemsDone) throws Exception {
        UUID id = UUID.randomUUID();
        admin("INSERT INTO plan_cycle (id, user_id, cycle_start, cycle_end, start_day, status) VALUES ('" + id
                + "', '" + user + "', DATE '" + start + "', DATE '" + start.plusMonths(1) + "', 1, 'CLOSED')");
        int n = 0;
        for (boolean done : itemsDone) {
            admin("INSERT INTO checklist_item (user_id, plan_cycle_id, type, source_ref, amount_paise, done_at) "
                    + "VALUES ('" + user + "', '" + id + "', 'SIP', 'ref-" + (n++) + "', 1, "
                    + (done ? "now()" : "NULL") + ")");
        }
    }

    @Test
    void threeDoneCyclesThenAMissedOneGivesZeroNotOne() throws Exception {
        as(a);
        closedCycle(a, ago(4), true, true);
        closedCycle(a, ago(3), true);
        closedCycle(a, ago(2), true);
        closedCycle(a, ago(1), true, false);
        JsonNode s = mvcGet("/checklist/streak");
        assertThat(s.get("streak").asInt()).isZero();
        assertThat(flags(s)).startsWith(true, true, true, false);
    }

    @Test
    void streakEqualsStatisticsConsistencyStreakOfTheSameFlags() throws Exception {
        as(a);
        closedCycle(a, ago(7), true);
        closedCycle(a, ago(6), false);
        closedCycle(a, ago(5), true, true);
        closedCycle(a, ago(4), true);
        closedCycle(a, ago(3));   // no items: counts as missed
        closedCycle(a, ago(2), true);
        closedCycle(a, ago(1), true);
        JsonNode s = mvcGet("/checklist/streak");
        // The open current cycle has no items yet, so it is shown as not done and does not enter the streak.
        List<Boolean> closed = flags(s);
        closed = closed.subList(0, closed.size() - 1);
        assertThat(closed).containsExactly(true, false, true, true, false, true, true);
        assertThat(s.get("streak").asInt()).isEqualTo(Statistics.consistencyStreak(closed)).isEqualTo(2);
    }

    @Test
    void anUnfinishedOpenCycleDoesNotResetTheStreakButACompleteOneExtendsIt() throws Exception {
        seedPersona();
        closedCycle(a, ago(2), true);
        closedCycle(a, ago(1), true);
        JsonNode v = generate();
        assertThat(mvcGet("/checklist/streak").get("streak").asInt()).isEqualTo(2);
        for (JsonNode i : v.get("items")) {
            mvc.perform(post("/checklist/items/" + i.get("id").asText() + "/done")).andExpect(status().isOk());
        }
        assertThat(mvcGet("/checklist/streak").get("streak").asInt()).isEqualTo(3);
    }

    @Test
    void historyIsCappedAtTwelveCyclesOldestFirst() throws Exception {
        as(a);
        for (int m = 0; m < 14; m++) {
            closedCycle(a, ago(14 - m), true);
        }
        JsonNode s = mvcGet("/checklist/streak");
        assertThat(s.get("history")).hasSize(12);
        assertThat(s.at("/history/0/cycleStart").asText()).isEqualTo(ago(11).toString());
        assertThat(s.get("streak").asInt()).isEqualTo(14);
    }

    @Test
    void tickingAnItemOfAClosedCycleIs409AndLeavesDoneAtNull() throws Exception {
        as(a);
        closedCycle(a, LocalDate.of(2020, 1, 1), false);
        String id = scalar("SELECT id FROM checklist_item WHERE user_id = '" + a + "'");
        mvc.perform(post("/checklist/items/" + id + "/done")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("cycle_closed"));
        assertThat(scalar("SELECT done_at IS NULL FROM checklist_item WHERE id = '" + id + "'")).isEqualTo("t");
    }

    @Test
    void aSkippedMonthBreaksTheStreakAndAppearsInTheHistoryAsNotDone() throws Exception {
        as(a);
        closedCycle(a, ago(5), true);
        closedCycle(a, ago(4), true);
        closedCycle(a, ago(2), true);   // ago(3) has no plan_cycle row
        closedCycle(a, ago(1), true);
        JsonNode s = mvcGet("/checklist/streak");
        List<Boolean> f = flags(s);
        assertThat(f.subList(0, 5)).containsExactly(true, true, false, true, true);
        assertThat(s.at("/history/2/cycleStart").asText()).isEqualTo(ago(3).toString());
        // the open current cycle is unfinished and neither counts nor resets; the run since the gap is 2
        assertThat(s.get("streak").asInt()).isEqualTo(2);
    }

    @Test
    void aShiftedCycleBoundaryIsNotReadAsAMissedCycle() throws Exception {
        as(a);
        closedCycle(a, ago(3), true);
        // boundary shifted by days (as after a start-day change): no whole cycle is missing
        UUID id = UUID.randomUUID();
        admin("INSERT INTO plan_cycle (id, user_id, cycle_start, cycle_end, start_day, status) VALUES ('" + id
                + "', '" + a + "', DATE '" + ago(2).plusDays(10) + "', DATE '" + ago(1).plusDays(10)
                + "', 11, 'CLOSED')");
        admin("INSERT INTO checklist_item (user_id, plan_cycle_id, type, source_ref, amount_paise, done_at) "
                + "VALUES ('" + a + "', '" + id + "', 'SIP', 'r', 1, now())");
        assertThat(flags(mvcGet("/checklist/streak")).subList(0, 2)).containsExactly(true, true);
        assertThat(mvcGet("/checklist/streak").get("streak").asInt()).isEqualTo(2);
    }

    @Test
    void anEmiEditUpdatesOpenItemsButDoneItemsKeepTheirAmount() throws Exception {
        seedPersona();
        JsonNode v = generate();
        String doneId = item(v, "EMI", homeLoan.toString()).get("id").asText();
        String openId = item(v, "EMI", goldLoan.toString()).get("id").asText();
        mvc.perform(post("/checklist/items/" + doneId + "/done")).andExpect(status().isOk());
        long oldHome = FX.paise("loan.home_emi_stated_paise");
        long newGold = FX.paise("loan.gold_monthly_interest_paise") + 100;
        new LoanRepository(() -> jdbcRef, () -> txRef).update(a, goldLoan, new LoanRepository.Draft("Gold lender",
                "bullet", FX.paise("loan.gold_principal_paise"), FX.decimal("loan.gold_rate"), newGold, false, null,
                LocalDate.of(2031, 10, 1), 0));
        new LoanRepository(() -> jdbcRef, () -> txRef).update(a, homeLoan, new LoanRepository.Draft("Home bank",
                "emi", FX.paise("loan.home_principal_paise"), FX.decimal("loan.home_rate"), oldHome + 100, false,
                FX.integer("loan.home_emis_left"), null, 0));
        assertThat(scalar("SELECT amount_paise FROM checklist_item WHERE id = '" + openId + "'"))
                .isEqualTo(String.valueOf(newGold));
        assertThat(scalar("SELECT amount_paise FROM checklist_item WHERE id = '" + doneId + "'"))
                .isEqualTo(String.valueOf(oldHome));
    }

    @Test
    void aPaidOffLoanYieldsNoEmiItemAndItsOpenItemIsDroppedOnEdit() throws Exception {
        seedPersona();
        generate();
        new LoanRepository(() -> jdbcRef, () -> txRef).update(a, goldLoan, new LoanRepository.Draft("Gold lender",
                "bullet", 0, FX.decimal("loan.gold_rate"), FX.paise("loan.gold_monthly_interest_paise"), false, null,
                LocalDate.of(2031, 10, 1), 0));
        assertThat(scalar("SELECT count(*) FROM checklist_item WHERE source_ref = '" + goldLoan + "'"))
                .isEqualTo("0");
        assertThat(generate().get("totalCount").asInt()).isEqualTo(4);
    }

    @Test
    void theAmountOfADoneItemCannotBeChangedEvenDirectly() throws Exception {
        seedPersona();
        String id = item(generate(), "SIP", "HABIT_SIP").get("id").asText();
        mvc.perform(post("/checklist/items/" + id + "/done")).andExpect(status().isOk());
        org.junit.jupiter.api.Assertions.assertThrows(java.sql.SQLException.class, () -> {
            try (Connection c = TestDb.asAppUser(a); Statement st = c.createStatement()) {
                st.execute("UPDATE checklist_item SET amount_paise = amount_paise + 1 WHERE id = '" + id + "'");
            }
        });
    }

    private static List<Boolean> flags(JsonNode streak) {
        List<Boolean> out = new ArrayList<>();
        streak.get("history").forEach(h -> out.add(h.get("done").asBoolean()));
        return out;
    }
}
