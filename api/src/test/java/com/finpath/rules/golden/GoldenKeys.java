package com.finpath.rules.golden;

import java.util.List;

/**
 * Key names of the golden fixture. Names only; values live in the fixture file. Comments
 * point at docs/PLAN.md section 7 where a figure deliberately differs from BRD prose.
 */
public final class GoldenKeys {

    private GoldenKeys() {
    }

    // Home loan (T11, T16)
    public static final String HOME_PRINCIPAL = "home.principal_paise";
    public static final String HOME_RATE_PCT = "home.rate_pct";
    public static final String HOME_TENURE_MONTHS = "home.tenure_months";
    public static final String HOME_STATED_EMI = "home.stated_emi_paise";
    public static final String HOME_INTEREST_REMAINING = "home.interest_remaining_paise";
    public static final String HOME_CLOSE_MONTHS = "home.close_months";
    public static final String HOME_CLOSE_EMI = "home.close_emi_paise";
    /** OQ-5, PLAN section 7: interest@tenure minus interest@close; not the BRD's rounded prose. */
    public static final String HOME_INTEREST_SAVED = "home.interest_saved_paise";

    // Bullet loan and sinking fund (T12, T13)
    public static final String BULLET_PRINCIPAL = "bullet.principal_paise";
    public static final String BULLET_MONTHLY_INTEREST = "bullet.monthly_interest_paise";
    public static final String BULLET_EFFECTIVE_RATE_PCT = "bullet.effective_rate_pct";
    public static final String BULLET_DUE_CYCLE = "bullet.due_cycle_id";
    public static final String BULLET_CYCLES_REMAINING = "bullet.cycles_remaining";
    public static final String SINKING_MONTHLY = "sinking.monthly_paise";

    // Roll-off (T15)
    public static final String ROLLOFF_1 = "rolloff.step1_paise";
    public static final String ROLLOFF_1_CYCLE = "rolloff.step1_cycle_id";
    public static final String ROLLOFF_2 = "rolloff.step2_paise";
    public static final String ROLLOFF_2_CYCLE = "rolloff.step2_cycle_id";
    public static final String ROLLOFF_3 = "rolloff.step3_paise";
    public static final String ROLLOFF_3_CYCLE = "rolloff.step3_cycle_id";

    // Income and EMI ratio (T17)
    public static final String INCOME_MONTHLY = "income.monthly_paise";
    public static final String INCOME_SALARY = "income.salary_paise";
    public static final String INCOME_RENTAL = "income.rental_paise";
    public static final String INCOME_BONUS = "income.bonus_paise";
    public static final String EMI_TOTAL = "emi.total_paise";
    public static final String EMI_RATIO = "emi.ratio";
    public static final String EMI_BLOCKED_RATIO = "emi.blocked_ratio";

    // Emergency fund (T18)
    public static final String OUTFLOW_MONTHLY = "outflow.monthly_paise";
    public static final String EMERGENCY_CURRENT = "emergency.current_paise";
    public static final String EMERGENCY_ONE_MONTH = "emergency.one_month_paise";
    public static final String EMERGENCY_THREE_MONTH = "emergency.three_month_paise";
    public static final String EMERGENCY_MONTHS_COVERED = "emergency.months_covered";

    // Dated goal (T19)
    public static final String GOAL_TARGET = "goal.target_paise";
    public static final String GOAL_CYCLES = "goal.cycles";
    public static final String GOAL_MONTHLY = "goal.monthly_paise";

    // FI (T20)
    public static final String FI_TODAY_EXPENSE = "fi.today_monthly_expense_paise";
    public static final String FI_HORIZON_YEARS = "fi.horizon_years";
    public static final String FI_INFLATED_EXPENSE = "fi.inflated_monthly_expense_paise";
    public static final String FI_GROSS_CORPUS = "fi.gross_corpus_paise";
    public static final String FI_PASSIVE_MONTHLY = "fi.passive_monthly_paise";
    public static final String FI_NET_CORPUS = "fi.net_corpus_paise";

    // Surplus (T22) and statistics (T25)
    public static final String SURPLUS_MONTHLY = "surplus.monthly_paise";
    /** OQ-6, PLAN section 7: pre-insurance roll-off figure; differs from the BRD prose on purpose. */
    public static final String SURPLUS_LATER = "surplus.later_paise";
    public static final String SURPLUS_LATER_CYCLE = "surplus.later_cycle_id";
    public static final String SAVINGS_RATE_PCT = "stats.savings_rate_pct";

    // Recurring bills (T38): a quarterly bill spread over three cycles (fixture-only; owner adds to the local file)
    public static final String QUARTERLY_BILL = "fixed.quarterly_bill_paise";

    // Category budget (T40): one budget and three spends (below 80%, 80-100%, at least 100%)
    public static final String BUDGET_CATEGORY = "budget.category_paise";
    public static final String BUDGET_SPENT = "budget.spent_paise";
    public static final String BUDGET_SPENT_WARN = "budget.spent_warn_paise";
    public static final String BUDGET_SPENT_BREACH = "budget.spent_breach_paise";

    public static final List<String> ALL = List.of(
            HOME_PRINCIPAL, HOME_RATE_PCT, HOME_TENURE_MONTHS, HOME_STATED_EMI, HOME_INTEREST_REMAINING,
            HOME_CLOSE_MONTHS, HOME_CLOSE_EMI, HOME_INTEREST_SAVED,
            BULLET_PRINCIPAL, BULLET_MONTHLY_INTEREST, BULLET_EFFECTIVE_RATE_PCT, BULLET_DUE_CYCLE,
            BULLET_CYCLES_REMAINING, SINKING_MONTHLY,
            ROLLOFF_1, ROLLOFF_1_CYCLE, ROLLOFF_2, ROLLOFF_2_CYCLE, ROLLOFF_3, ROLLOFF_3_CYCLE,
            INCOME_MONTHLY, INCOME_SALARY, INCOME_RENTAL, INCOME_BONUS, EMI_TOTAL, EMI_RATIO, EMI_BLOCKED_RATIO,
            OUTFLOW_MONTHLY, EMERGENCY_CURRENT, EMERGENCY_ONE_MONTH, EMERGENCY_THREE_MONTH,
            EMERGENCY_MONTHS_COVERED,
            GOAL_TARGET, GOAL_CYCLES, GOAL_MONTHLY,
            FI_TODAY_EXPENSE, FI_HORIZON_YEARS, FI_INFLATED_EXPENSE, FI_GROSS_CORPUS, FI_PASSIVE_MONTHLY,
            FI_NET_CORPUS,
            SURPLUS_MONTHLY, SURPLUS_LATER, SURPLUS_LATER_CYCLE, SAVINGS_RATE_PCT, QUARTERLY_BILL,
            BUDGET_CATEGORY, BUDGET_SPENT, BUDGET_SPENT_WARN, BUDGET_SPENT_BREACH);
}
