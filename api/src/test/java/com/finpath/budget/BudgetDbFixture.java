package com.finpath.budget;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.finpath.auth.CurrentUser;
import com.finpath.auth.CurrentUserIdArgumentResolver;
import com.finpath.db.TestDb;
import com.finpath.dpdp.ConsentService;
import com.finpath.dpdp.ConsentStatement;
import com.finpath.expense.TransactionRepository;
import com.finpath.expense.TransactionRepository.Draft;
import com.finpath.rls.RlsTransactionManager;
import com.finpath.rules.RuleConfig;
import com.finpath.rules.TransactionHash.Direction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real budget services over the scratch database under RLS (callers skip without one). The clock is
 * fixed at 2026-10-22 and users have cycle start day 1, so the cycle is 2026-10-01 to 2026-11-01.
 */
final class BudgetDbFixture {

    static final LocalDate IN_CYCLE = LocalDate.parse("2026-10-10");
    static final LocalDate BEFORE_CYCLE = LocalDate.parse("2026-09-30");
    static final LocalDate AFTER_CYCLE = LocalDate.parse("2026-11-01");

    final ConsentService consents;
    final TransactionRepository transactions;
    final BudgetRepository repository;
    final BudgetService service;
    final BudgetAlertService alerts;
    final MockMvc mvc;
    private int seq;

    BudgetDbFixture() {
        TestDb.requireAndMigrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(TestDb.URL, TestDb.USER, TestDb.PASSWORD);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(new RlsTransactionManager(ds, CurrentUser::id));
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("jdbc", jdbc);
        bf.registerSingleton("tx", tx);
        consents = new ConsentService(bf.getBeanProvider(JdbcTemplate.class),
                bf.getBeanProvider(TransactionTemplate.class));
        transactions = new TransactionRepository(() -> jdbc, () -> tx);
        repository = new BudgetRepository(() -> jdbc, () -> tx);
        Clock clock = Clock.fixed(Instant.parse("2026-10-22T06:30:00Z"), ZoneId.of("Asia/Kolkata"));
        service = new BudgetService(repository, transactions, consents, RuleConfig.defaults(), clock);
        alerts = new BudgetAlertService(repository, transactions, consents, RuleConfig.defaults(), clock);
        ObjectMapper om = new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mvc = MockMvcBuilders.standaloneSetup(new BudgetController(service, alerts))
                .setControllerAdvice(new BudgetExceptionHandler())
                .setCustomArgumentResolvers(new CurrentUserIdArgumentResolver())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(om)).build();
    }

    UUID newUser(boolean consent) throws SQLException {
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

    static void as(UUID user) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(user.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    static void clearPrincipal() {
        SecurityContextHolder.clearContext();
    }

    /** Stores one transaction for {@code user} (the principal is switched to that user). */
    void spend(UUID user, Direction direction, String category, long paise, LocalDate date) {
        as(user);
        transactions.insert(user, new Draft(date, paise, direction, category, "UPI", null, null,
                "budget-test-" + user + "-" + (++seq)));
    }
}
