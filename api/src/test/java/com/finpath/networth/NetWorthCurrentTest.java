package com.finpath.networth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finpath.dpdp.ConsentRequiredException;
import com.finpath.dpdp.ConsentService;
import com.finpath.rules.golden.GoldenFixture;
import com.finpath.rules.golden.GoldenKeys;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** T74: net worth now uses the cycle-close snapshot's inputs and rule, so the two cannot drift apart. */
class NetWorthCurrentTest {

    private static final GoldenFixture FX = GoldenFixture.shared();
    private static final long ASSETS = FX.paise(GoldenKeys.EMERGENCY_CURRENT);
    private static final long LIABILITIES = FX.paise(GoldenKeys.EMI_TOTAL);

    private final UUID user = UUID.randomUUID();
    private JdbcTemplate jdbc;
    private ConsentService consents;
    private NetWorthService service;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        consents = mock(ConsentService.class);
        TransactionTemplate tx = mock(TransactionTemplate.class);
        when(tx.execute(any())).thenAnswer(i -> ((TransactionCallback<Object>) i.getArgument(0)).doInTransaction(null));
        when(jdbc.queryForObject(anyString(), eq(Long.class), eq(user))).thenAnswer(i ->
                ((String) i.getArgument(0)).contains("FROM holding") ? ASSETS : LIABILITIES);
        service = new NetWorthService(() -> jdbc, () -> tx, consents);
    }

    @Test
    void currentIsAssetsMinusLiabilities() {
        var p = service.current(user);
        assertThat(p.assetsPaise()).isEqualTo(ASSETS);
        assertThat(p.liabilitiesPaise()).isEqualTo(LIABILITIES);
        assertThat(p.netWorthPaise()).isEqualTo(ASSETS - LIABILITIES);
    }

    @Test
    void currentEqualsTheSnapshotWrittenFromTheSameInputs() {
        var now = service.current(user);
        UUID cycleId = UUID.randomUUID();
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);
        NetWorthSnapshots.recordAtClose(jdbc, user, cycleId, LocalDate.of(2026, 9, 1));
        ArgumentCaptor<Object[]> args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertThat(args.getValue()).containsSubsequence(now.assetsPaise(), now.liabilitiesPaise(),
                now.netWorthPaise());
    }

    @Test
    void requiresConsent() {
        doThrow(new ConsentRequiredException()).when(consents).require(user);
        assertThatThrownBy(() -> service.current(user)).isInstanceOf(ConsentRequiredException.class);
        verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }
}
