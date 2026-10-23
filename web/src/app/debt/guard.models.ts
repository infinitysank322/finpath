/** GET /loans/debt-trap-guard, and the `guard` of a 409 debt_trap_blocked. Ratio is a decimal string, null when income is zero. */
export type GuardStatus = 'OK' | 'WARN' | 'BLOCK';

export interface GuardView {
  ratio: string | null;
  status: GuardStatus;
  headroomPaise: number;
  totalEmiPaise: number;
  monthlyIncomePaise: number;
}
