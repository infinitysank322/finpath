package com.finpath.goal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finpath.dpdp.ConsentRequiredException;
import com.finpath.dpdp.ConsentService;
import com.finpath.goal.EmergencyFundService.EmergencyFundView;
import com.finpath.goal.GoalRepository.Row;
import com.finpath.expense.TransactionRepository;
import com.finpath.expense.TransactionRepository.Transaction;
import com.finpath.loan.LoanService;
import com.finpath.loan.LoanService.GuardView;
import com.finpath.recurring.RecurringService;
import com.finpath.recurring.RecurringService.TemplateView;
import com.finpath.rules.RecurringSplit.Frequency;
import com.finpath.rules.golden.GoldenFixture;
import com.finpath.rules.golden.GoldenKeys;
import com.finpath.rules.TransactionHash.Direction;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** T62: the emergency target is sized from actual outflow on every read. Figures come from the fixture. */
class EmergencyFundServiceTest {

    private static final GoldenFixture FX = GoldenFixture.shared();
    private static final long OUTFLOW = FX.paise(GoldenKeys.OUTFLOW_MONTHLY);
    private static final long EMIS = FX.paise(GoldenKeys.EMI_TOTAL);
    private static final long CURRENT = FX.paise(GoldenKeys.EMERGENCY_CURRENT);

    private final UUID user = UUID.randomUUID();
    private LoanService loans;
    private RecurringService recurring;
    private GoalRepository goals;
    private ConsentService consents;
    private TransactionRepository txns;
    private EmergencyFundService service;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 22);

    @BeforeEach
    void setUp() {
        loans = mock(LoanService.class);
        recurring = mock(RecurringService.class);
        goals = mock(GoalRepository.class);
        consents = mock(ConsentService.class);
        txns = mock(TransactionRepository.class);
        service = new EmergencyFundService(loans, recurring, goals, consents, txns,
                Clock.fixed(TODAY.atStartOfDay(IST).toInstant(), IST));
        when(goals.cycleStartDay(user)).thenReturn(Optional.of(1));
        stub(EMIS, OUTFLOW - EMIS, CURRENT);
        actual(0L);
    }

    private void stub(long emis, long fixed, long saved) {
        when(loans.guard(user)).thenReturn(new GuardView("0", "OK", 0L, emis, 0L));
        when(recurring.list(user)).thenReturn(List.of(template(fixed), template(null)));
        when(goals.list(user)).thenReturn(List.of(goal("emergency", saved), goal("marriage", 999L)));
    }

    /** Last cycle (1 Sep..1 Oct) holds one OUT of the given size plus an IN and an out-of-cycle OUT that must be ignored. */
    private void actual(long outPaise) {
        when(txns.listBetween(user, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 10, 1))).thenReturn(List.of(
                txn(outPaise, Direction.OUT), txn(7_777_777L, Direction.IN)));
    }

    private static Transaction txn(long paise, Direction d) {
        return new Transaction(UUID.randomUUID(), LocalDate.of(2026, 9, 10), paise, d, "FOOD", "UPI", null,
                "MANUAL", null, "h", null);
    }

    private static TemplateView template(Long instalment) {
        return new TemplateView(UUID.randomUUID(), "RENT", 1L, Frequency.MONTHLY, "UPI", "NEED",
                LocalDate.of(2026, 1, 1), null, List.of(), instalment);
    }

    private static Row goal(String type, long current) {
        return new Row(UUID.randomUUID(), type, 1L, LocalDate.of(2030, 1, 1), current, 5, Instant.EPOCH);
    }

    @Test
    void oneAndThreeMonthFiguresMatchTheFixture() {
        EmergencyFundView v = service.size(user, null);
        assertThat(v.monthlyOutflowPaise()).isEqualTo(OUTFLOW);
        assertThat(v.oneMonthPaise()).isEqualTo(FX.paise(GoldenKeys.EMERGENCY_ONE_MONTH));
        assertThat(v.targetMonths()).isEqualTo(3);
        assertThat(v.targetPaise()).isEqualTo(FX.paise(GoldenKeys.EMERGENCY_THREE_MONTH));
        assertThat(v.currentPaise()).isEqualTo(CURRENT);
        assertThat(v.monthsCovered()).isEqualTo(FX.text(GoldenKeys.EMERGENCY_MONTHS_COVERED));
        assertThat(v.shortfallPaise()).isEqualTo(v.targetPaise() - CURRENT);
    }

    @Test
    void targetRecomputesWhenOutflowChanges() {
        long before = service.size(user, 3).targetPaise();
        stub(EMIS + 100_000L, OUTFLOW - EMIS, CURRENT);
        EmergencyFundView after = service.size(user, 3);
        assertThat(after.monthlyOutflowPaise()).isEqualTo(OUTFLOW + 100_000L);
        assertThat(after.targetPaise()).isEqualTo(before + 300_000L);
    }

    @Test
    void targetMonthsIsBoundedToOneToSix() {
        assertThat(service.size(user, 6).targetPaise()).isEqualTo(OUTFLOW * 6);
        assertThatThrownBy(() -> service.size(user, 0)).isInstanceOf(InvalidGoalException.class);
        assertThatThrownBy(() -> service.size(user, 7)).isInstanceOf(InvalidGoalException.class);
    }

    @Test
    void actualSpendingBeyondCommitmentsSizesTheTarget() {
        actual(OUTFLOW + 500_000L);
        EmergencyFundView v = service.size(user, 3);
        assertThat(v.monthlyOutflowPaise()).isEqualTo(OUTFLOW + 500_000L);
        assertThat(v.targetPaise()).isEqualTo((OUTFLOW + 500_000L) * 3);
        stub(0L, 0L, CURRENT);
        assertThat(service.size(user, 1).monthlyOutflowPaise()).isEqualTo(OUTFLOW + 500_000L);
    }

    @Test
    void twoEmergencyGoalsAreSummedAndOtherTypesIgnored() {
        when(goals.list(user)).thenReturn(List.of(goal("emergency", 100L), goal("emergency", 250L), goal("home", 9L)));
        assertThat(service.size(user, 3).currentPaise()).isEqualTo(350L);
    }

    @Test
    void metTargetHasNoShortfallAndZeroOutflowHasNoCover() {
        stub(EMIS, OUTFLOW - EMIS, OUTFLOW * 4);
        assertThat(service.size(user, 3).shortfallPaise()).isZero();
        stub(0L, 0L, CURRENT);
        EmergencyFundView none = service.size(user, 3);
        assertThat(none.targetPaise()).isZero();
        assertThat(none.monthsCovered()).isNull();
    }

    @Test
    void consentIsRequiredAndCheckedBeforeAnyRead() {
        org.mockito.Mockito.doThrow(new ConsentRequiredException()).when(consents).require(user);
        assertThatThrownBy(() -> service.size(user, 3)).isInstanceOf(ConsentRequiredException.class);
        verify(loans, never()).guard(any());
    }
}
