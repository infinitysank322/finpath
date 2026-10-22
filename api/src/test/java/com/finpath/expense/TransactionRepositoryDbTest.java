package com.finpath.expense;

import static org.assertj.core.api.Assertions.assertThat;

import com.finpath.db.TestDb;
import com.finpath.dpdp.ConsentService;
import com.finpath.dpdp.ConsentStatement;
import com.finpath.expense.TransactionRepository.Draft;
import com.finpath.expense.TransactionRepository.InsertResult;
import com.finpath.rls.RlsTransactionManager;
import com.finpath.rules.TransactionHash.Direction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** TransactionRepository against a real database under RLS (needs the scratch DB; skipped without one). */
class TransactionRepositoryDbTest {

    private static volatile UUID current;

    private TransactionRepository repo;
    private UUID user;

    @BeforeEach
    void setUp() throws SQLException {
        TestDb.requireAndMigrate();
        DriverManagerDataSource ds = new DriverManagerDataSource(TestDb.URL, TestDb.USER, TestDb.PASSWORD);
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        TransactionTemplate tx = new TransactionTemplate(
                new RlsTransactionManager(ds, () -> Optional.ofNullable(current)));
        DefaultListableBeanFactory bf = new DefaultListableBeanFactory();
        bf.registerSingleton("jdbc", jdbc);
        bf.registerSingleton("tx", tx);
        ConsentService consents = new ConsentService(bf.getBeanProvider(JdbcTemplate.class),
                bf.getBeanProvider(TransactionTemplate.class));
        repo = new TransactionRepository(() -> jdbc, () -> tx);
        user = newUser();
        current = user;
        consents.grant(user, ConsentStatement.CURRENT.purpose(), ConsentStatement.CURRENT.version());
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

    private static Draft draft(long paise, String key) {
        return new Draft(LocalDate.of(2026, 10, 22), paise, Direction.OUT, "dining", "upi", null, null, key);
    }

    @Test
    void replayOfTheSameIdempotencyKeyReturnsTheOriginalAndStoresOneRow() {
        InsertResult first = repo.insert(user, draft(4000, "key-00000001"));
        InsertResult replay = repo.insert(user, draft(9999, "key-00000001"));

        assertThat(first.created()).isTrue();
        assertThat(replay.created()).isFalse();
        assertThat(replay.row().id()).isEqualTo(first.row().id());
        assertThat(replay.row().amountPaise()).isEqualTo(4000);
        assertThat(repo.listBetween(user, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1))).hasSize(1);
        assertThat(first.row().source()).isEqualTo("MANUAL");
        assertThat(first.row().categoryCode()).isEqualTo("DINING");
    }

    @Test
    void identicalInsertsShareAHashAndAChangedAmountDiffers() {
        InsertResult a = repo.insert(user, draft(4000, "key-00000001"));
        InsertResult b = repo.insert(user, draft(4000, "key-00000002"));
        InsertResult c = repo.insert(user, draft(4001, "key-00000003"));

        assertThat(b.created()).isTrue();
        assertThat(a.row().id()).isNotEqualTo(b.row().id());
        assertThat(a.row().dedupeHash()).isEqualTo(b.row().dedupeHash()).hasSize(64);
        assertThat(c.row().dedupeHash()).isNotEqualTo(a.row().dedupeHash());
    }

    @Test
    void updateRecomputesTheHashAndKeepsTheIdempotencyKey() {
        InsertResult a = repo.insert(user, draft(4000, "key-00000001"));
        TransactionRepository.Transaction updated = repo.update(user, a.row().id(), draft(5000, "ignored-key")).orElseThrow();

        assertThat(updated.amountPaise()).isEqualTo(5000);
        assertThat(updated.dedupeHash()).isNotEqualTo(a.row().dedupeHash());
        assertThat(updated.idempotencyKey()).isEqualTo("key-00000001");
    }

    @Test
    void anotherUserCannotFindUpdateOrDeleteMyRow() throws SQLException {
        InsertResult mine = repo.insert(user, draft(4000, "key-00000001"));
        UUID other = newUser();
        current = other;

        assertThat(repo.findById(other, mine.row().id())).isEmpty();
        assertThat(repo.findByIdempotencyKey(other, "key-00000001")).isEmpty();
        assertThat(repo.update(other, mine.row().id(), draft(1, "key-00000009"))).isEmpty();
        assertThat(repo.delete(other, mine.row().id())).isFalse();

        current = user;
        assertThat(repo.findById(user, mine.row().id())).isPresent();
        assertThat(repo.delete(user, mine.row().id())).isTrue();
        assertThat(repo.findById(user, mine.row().id())).isEmpty();
    }

}
