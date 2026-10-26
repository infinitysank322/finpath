/** Slice of GET /dashboard (T74) the shell needs. Amounts are paise; the rest of the payload is kept verbatim. */
export interface DashboardView {
  /** ISO date (Asia/Kolkata "today") every figure was computed for. */
  asOf: string;
  cycle: { start: string; endExclusive: string; lengthDays: number; daysElapsed: number; daysLeft: number };
  budget: BudgetSummary;
  netWorth?: NetWorthSection;
  fi?: FiSection;
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

/** GET /dashboard netWorth section: snapshot series, oldest first. Never recomputed client-side. */
export interface NetWorthSection {
  nowPaise: number;
  series: { cycleStart: string; assetsPaise: number; liabilitiesPaise: number; netWorthPaise: number }[];
}

/** GET /dashboard fi section (T74). fiYears/fiDate null = not reached in the solve horizon. */
export interface FiSection {
  status: 'OK' | 'NOT_SET_UP';
  bands: { band: string; annualReturn: string; fiYears: number | null; fiDate: string | null }[];
  base: { fiYears: number | null; fiDate: string | null } | null;
  /** Whole years of base fiYears vs last cycle; negative = closer, positive = further; null until a prior figure is stored. */
  deltaYearsVsLastCycle: number | null;
  assumptions: { inflation: string; debtReturnHigh: string; equityReturn: string; fiMultiplier: number; stepUp: string };
  disclaimer: string;
}
