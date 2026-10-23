package com.finpath.goal;

import com.finpath.dpdp.ConsentService;
import com.finpath.loan.LoanService;
import com.finpath.recurring.RecurringService;
import com.finpath.expense.TransactionRepository;
import com.finpath.rules.BudgetRules;
import com.finpath.rules.CycleCalendar;
import com.finpath.rules.EmergencyFund;
import com.finpath.rules.TransactionHash;
import com.finpath.rules.Money;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * FR-20 emergency-fund sizing. The target is derived on every read from the caller's actual outflow
 * (OUT transactions of the last completed cycle, floored at the active loan EMIs plus the current
 * recurring instalments), so it moves when either changes and is never stored. Every figure comes from {@link EmergencyFund}; this class only gathers
 * inputs. Logs nothing.
 */
@Service
public class EmergencyFundService {

    static final int DEFAULT_TARGET_MONTHS = 3;
    static final int MAX_TARGET_MONTHS = 6;

    /** monthsCovered is a decimal string, null when there is no outflow to cover. */
    public record EmergencyFundView(long monthlyOutflowPaise, long oneMonthPaise, int targetMonths,
            long targetPaise, long currentPaise, String monthsCovered, long shortfallPaise) {
    }

    private final LoanService loans;
    private final RecurringService recurring;
    private final GoalRepository goals;
    private final ConsentService consents;
    private final TransactionRepository transactions;
    private final Clock clock;

    @Autowired
    public EmergencyFundService(LoanService loans, RecurringService recurring, GoalRepository goals,
            ConsentService consents, TransactionRepository transactions) {
        this(loans, recurring, goals, consents, transactions, Clock.system(ZoneId.of("Asia/Kolkata")));
    }

    EmergencyFundService(LoanService loans, RecurringService recurring, GoalRepository goals,
            ConsentService consents, TransactionRepository transactions, Clock clock) {
        this.loans = loans;
        this.recurring = recurring;
        this.goals = goals;
        this.consents = consents;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** targetMonths defaults to 3 and must be 1..6 (BRD 3-6 month target); otherwise InvalidGoalException. */
    public EmergencyFundView size(UUID userId, Integer targetMonths) {
        int months = targetMonths == null ? DEFAULT_TARGET_MONTHS : targetMonths;
        if (months < 1 || months > MAX_TARGET_MONTHS) {
            throw new InvalidGoalException();
        }
        consents.require(userId);
        int startDay = goals.cycleStartDay(userId).orElseThrow(GoalNotFoundException::new);
        CycleCalendar.Cycle last = CycleCalendar.cycleFor(
                CycleCalendar.cycleFor(LocalDate.now(clock), startDay).start().minusDays(1), startDay);
        Money actual = BudgetRules.sum(transactions.listBetween(userId, last.start(), last.endExclusive()).stream()
                .filter(t -> t.direction() == TransactionHash.Direction.OUT)
                .map(t -> Money.ofPaise(t.amountPaise())).toList());
        List<Money> committed = new java.util.ArrayList<>();
        committed.add(Money.ofPaise(loans.guard(userId).totalEmiPaise()));
        recurring.list(userId).forEach(t -> committed
                .add(Money.ofPaise(t.currentInstalmentPaise() == null ? 0L : t.currentInstalmentPaise())));
        Money outflow = EmergencyFund.monthlyOutflow(actual, BudgetRules.sum(committed));
        Money current = BudgetRules.sum(goals.list(userId).stream().filter(g -> GoalService.EMERGENCY.equals(g.type()))
                .map(g -> Money.ofPaise(g.currentAmountPaise())).toList());
        long saved = current.paise();
        Money target = EmergencyFund.target(outflow, months);
        var covered = EmergencyFund.monthsCovered(current, outflow);
        return new EmergencyFundView(outflow.paise(), EmergencyFund.target(outflow, 1).paise(), months,
                target.paise(), saved, covered == null ? null : covered.toPlainString(),
                EmergencyFund.shortfall(current, target).paise());
    }
}
