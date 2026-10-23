package com.finpath.allocation;

import static org.assertj.core.api.Assertions.assertThat;

import com.finpath.auth.CurrentUser;
import com.finpath.db.TestDb;
import com.finpath.dpdp.ConsentService;
import com.finpath.dpdp.ConsentStatement;
import com.finpath.rls.RlsTransactionManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
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

/** T64 persistence under RLS (skipped without a scratch database): overwrite is idempotent and per user. */
class AllocationRepositoryDbTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 1);

    private AllocationRepository repo;
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
        repo = new AllocationRepository(() -> jdbc, () -> tx);
        a = newUser(consents);
        b = newUser(consents);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static UUID newUser(ConsentService consents) throws Exception {
        UUID id = UUID.randomUUID();
        try (Connection c = TestDb.admin()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO users (id, name, email, cycle_start_day) VALUES (?, 'T', ?, 1)")) {
                ps.setObject(1, id);
                ps.setString(2, id + "@example.test");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO plan_cycle (user_id, cycle_start, cycle_end, "
                    + "start_day) VALUES (?, DATE '2026-10-01', DATE '2026-11-01', 1)")) {
                ps.setObject(1, id);
                ps.executeUpdate();
            }
        }
        as(id);
        consents.grant(id, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
        return id;
    }

    private static void as(UUID user) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(user.toString()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
    }

    @Test
    void saveOverwritesTheOpenRowAndIsScopedToTheCaller() {
        as(a);
        String json = "[{\"step\":\"LONG_TERM_SIP\",\"ref\":null,\"amountPaise\":500}]";
        assertThat(repo.save(a, START, 500L, json)).isTrue();
        assertThat(repo.save(a, START, 500L, json)).isTrue();
        AllocationRepository.Stored s = repo.open(a).orElseThrow();
        assertThat(s.surplusPaise()).isEqualTo(500L);
        assertThat(s.cycleStart()).isEqualTo(START);
        as(b);
        assertThat(repo.save(a, START, 1L, "[]")).isFalse();
        assertThat(repo.open(b).orElseThrow().surplusPaise()).isNull();
        as(a);
        assertThat(repo.open(a).orElseThrow().surplusPaise()).isEqualTo(500L);
    }
}
