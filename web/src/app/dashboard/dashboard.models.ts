/** Slice of GET /dashboard (T74) the shell needs. Amounts are paise; the rest of the payload is kept verbatim. */
export interface DashboardView {
  /** ISO date (Asia/Kolkata "today") every figure was computed for. */
  asOf: string;
  cycle: { start: string; endExclusive: string; lengthDays: number; daysElapsed: number; daysLeft: number };
  budget: { status: 'OK' | 'NOT_SET_UP'; safeToSpendPerDayPaise: number | null; [k: string]: unknown };
  [k: string]: unknown;
}

/** The last good dashboard for one user and when this device fetched it. */
export interface CachedDashboard {
  data: DashboardView;
  /** Epoch ms of the successful fetch. */
  savedAt: number;
}
