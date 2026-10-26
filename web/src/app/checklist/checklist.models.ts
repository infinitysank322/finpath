/** Wire shapes of /checklist (T71). Values are displayed as given; nothing here is derived. */
export interface ChecklistItem {
  id: string;
  type: 'EMI' | 'SIP' | 'GOAL_TRANSFER';
  sourceRef: string;
  label: string;
  amountPaise: number;
  done: boolean;
  doneAt: string | null;
}

export interface ChecklistView {
  cycleStart: string;
  items: ChecklistItem[];
  doneCount: number;
  totalCount: number;
}

export interface CycleFlag {
  cycleStart: string;
  done: boolean;
}

export interface StreakView {
  streak: number;
  history: CycleFlag[];
}
