/** Slice of GET /dashboard (T74) the shell needs. Amounts are paise; the rest of the payload is kept verbatim. */
export interface DashboardView {
  /** ISO date (Asia/Kolkata "today") every figure was computed for. */
  asOf: string;
  cycle: { start: string; endExclusive: string; lengthDays: number; daysElapsed: number; daysLeft: number };
  budget: BudgetSummary;
  [k: string]: unknown;
}

/** GET /dashboard budget section (T74). Figures are null when status is NOT_SET_UP. Ratios are decimal strings. */
export interface BudgetSummary {
  status: 'OK' | 'NOT_SET_UP';
  budgetPaise: number | null;
  spentPaise: number | null;
  remainingPaise: number | null;
  usedFraction: string | null;
  level: 'OK' | 'WARN' | 'BREACH' | null;
  safeToSpendPerDayPaise: number | null;
}

/** The last good dashboard for one user and when this device fetched it. */
export interface CachedDashboard {
  data: DashboardView;
  /** Epoch ms of the successful fetch. */
  savedAt: number;
}
