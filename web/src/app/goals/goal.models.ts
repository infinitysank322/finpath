/** Mirrors the T61 API (GoalController). Money is integer paise; nothing here is computed client-side. */
export type GoalType = 'emergency' | 'marriage' | 'education' | 'home' | 'health' | 'vehicle' | 'travel' | 'other';
export type GoalStatusCode = 'ACHIEVED' | 'ON_TRACK' | 'BEHIND' | 'PAST_DUE';

export interface GoalInput {
  type: GoalType;
  targetAmountPaise: number;
  targetDate: string; // yyyy-MM-dd
  currentAmountPaise: number;
  priority: number; // 1..10
}

export interface GoalView extends GoalInput {
  id: string;
  createdAt?: string;
}

export interface GoalStatusView {
  cyclesRemaining: number;
  shortfallPaise: number;
  monthlyNeedPaise: number;
  plannedMonthlyPaise: number;
  status: GoalStatusCode;
  onTrack: boolean;
  instrumentClass: 'SAFE_ONLY' | 'EQUITY_ALLOWED';
}

/** The closed list from the T61 design note; widening it needs an API migration first. */
export const GOAL_TYPES: { code: GoalType; label: string }[] = [
  { code: 'emergency', label: 'Emergency fund' },
  { code: 'marriage', label: 'Marriage' },
  { code: 'education', label: 'Education' },
  { code: 'home', label: 'Home' },
  { code: 'health', label: 'Health' },
  { code: 'vehicle', label: 'Vehicle' },
  { code: 'travel', label: 'Travel' },
  { code: 'other', label: 'Other' },
];

/** Text and a glyph for each status, so meaning never rests on colour. */
export const GOAL_STATUS_TEXT: Record<GoalStatusCode, { label: string; icon: string }> = {
  ACHIEVED: { label: 'Achieved', icon: '★' },
  ON_TRACK: { label: 'On track', icon: '✓' },
  BEHIND: { label: 'Behind', icon: '▼' },
  PAST_DUE: { label: 'Past due', icon: '!' },
};
