import { PaymentMode, SpendTag } from '../expense/expense.models';

export type IncomeType = 'SALARY' | 'RENTAL' | 'BONUS' | 'SIDE' | 'OTHER';
export type IncomeFrequency = 'MONTHLY' | 'ONE_OFF';
export type RecurringFrequency = 'MONTHLY' | 'QUARTERLY' | 'YEARLY';

export interface IncomeInput {
  type: IncomeType;
  frequency: IncomeFrequency;
  amountPaise: number; // integer
  startDate: string; // yyyy-MM-dd
  endDate: string | null;
}

export interface IncomeView extends IncomeInput {
  id: string;
  countsInCurrentCycle: boolean;
}

export interface PlanCycleView {
  cycleStart: string;
  cycleEndExclusive: string;
  startDay: number;
  status: string;
}

export interface CycleTotalView {
  cycleStart: string;
  cycleEndExclusive: string;
  totalPaise: number;
  items: { id: string; type: IncomeType; amountPaise: number }[];
}

export interface TemplateInput {
  categoryCode: string;
  amountPaise: number; // the bill per period, not the instalment
  frequency: RecurringFrequency;
  mode: PaymentMode;
  tag: SpendTag | null;
  startDate: string;
  endDate: string | null;
}

export interface TemplateView extends TemplateInput {
  id: string;
  perCycleSpread: { index: number; amountPaise: number }[];
  currentInstalmentPaise: number | null;
}

export interface MaterialiseResult {
  cycleStart: string;
  cycleEndExclusive: string;
  items: { templateId: string; instalmentIndex: number; instalments: number; amountPaise: number; created: boolean }[];
  createdCount: number;
  postedPaise: number;
}

export const INCOME_TYPES: { code: IncomeType; label: string }[] = [
  { code: 'SALARY', label: 'Salary' },
  { code: 'RENTAL', label: 'Rental' },
  { code: 'BONUS', label: 'Bonus' },
  { code: 'SIDE', label: 'Side income' },
  { code: 'OTHER', label: 'Other' },
];

export const INCOME_FREQUENCIES: { code: IncomeFrequency; label: string }[] = [
  { code: 'MONTHLY', label: 'Recurring' },
  { code: 'ONE_OFF', label: 'One-off' },
];

/** Labels only; the instalment split itself always comes from the API. */
export const RECURRING_FREQUENCIES: { code: RecurringFrequency; label: string; every: string }[] = [
  { code: 'MONTHLY', label: 'Monthly', every: 'every month' },
  { code: 'QUARTERLY', label: 'Quarterly', every: 'every 3 months' },
  { code: 'YEARLY', label: 'Yearly', every: 'every 12 months' },
];
