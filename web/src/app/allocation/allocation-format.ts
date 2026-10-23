const NAMES: Record<string, string> = {
  EMERGENCY_FUND_ONE_MONTH: 'Emergency fund: first month',
  HIGH_INTEREST_DEBT: 'High-interest debt',
  EMERGENCY_FUND_TARGET: 'Emergency fund: to target',
  SAFE_GOALS: 'Goals in safe instruments',
  HABIT_SIP: 'Minimum habit SIP',
  DEBT_PREPAYMENT: 'Debt prepayment',
  LONG_TERM_SIP: 'Long-term SIP',
};

/** Plain words for an API step code; unknown codes fall back to a tidied form so nothing is hidden. */
export function stepName(code: string): string {
  return NAMES[code] ?? code.toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());
}
