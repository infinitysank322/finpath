export type BudgetLevel = 'OK' | 'WARN' | 'BREACH';

/** One category line, copied by the API from BudgetRules.Status (T40 section 4). Never recomputed here. */
export interface BudgetLine {
  categoryCode: string;
  budgetPaise: number;
  spentPaise: number;
  remainingPaise: number;
  /** Decimal string such as "0.8500"; null when the budget is 0. */
  usedFraction: string | null;
  level: BudgetLevel;
  lastAlertLevel: BudgetLevel;
}

export interface BudgetStatus {
  cycleStart: string;
  cycleEndExclusive: string;
  lines: BudgetLine[];
}

export interface BudgetEvent {
  categoryCode: string;
  level: BudgetLevel;
}

export interface EvaluateResult {
  events: BudgetEvent[];
}

export interface BudgetInput {
  budgetPaise: number;
}
