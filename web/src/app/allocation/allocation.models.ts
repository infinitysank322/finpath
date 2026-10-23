/** Mirrors AllocationService.AllocationView and RebalanceService.Preview. Money is integer paise. */
export interface AllocationLine {
  step: string;
  ref: string | null;
  amountPaise: number;
}

export interface AllocationView {
  cycleStart: string;
  surplusPaise: number;
  shortfallPaise: number;
  allocatedPaise: number;
  prepayShare: string | null;
  lines: AllocationLine[];
}

export interface RebalancePreview {
  absorbed: AllocationLine[];
  primaryAbsorbingStep: string | null;
  uncoveredPaise: number;
  surplusAfterPaise: number;
  lines: AllocationLine[];
  safeToSpendBeforePaise: number;
  safeToSpendAfterPaise: number;
}
