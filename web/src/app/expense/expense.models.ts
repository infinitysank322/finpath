export type PaymentMode = 'UPI' | 'CARD' | 'CASH' | 'NETBANKING' | 'OTHER';
export type SpendTag = 'NEED' | 'WANT' | 'WASTE';

export interface ExpenseInput {
  txnDate: string; // yyyy-MM-dd
  amountPaise: number; // integer
  categoryCode: string;
  mode: PaymentMode;
  tag: SpendTag | null;
  note: string | null;
}

export interface ExpenseView extends ExpenseInput {
  id: string;
  source: string;
  possibleDuplicate: boolean;
}

export interface CategoryOption {
  code: string;
  name: string;
}

export const MODES: { code: PaymentMode; label: string }[] = [
  { code: 'UPI', label: 'UPI' },
  { code: 'CARD', label: 'Card' },
  { code: 'CASH', label: 'Cash' },
  { code: 'NETBANKING', label: 'Net banking' },
  { code: 'OTHER', label: 'Other' },
];

export const TAGS: { code: SpendTag; label: string }[] = [
  { code: 'NEED', label: 'Need' },
  { code: 'WANT', label: 'Want' },
  { code: 'WASTE', label: 'Waste' },
];

/**
 * OUT categories from the V6 seed (docs/design/T36). The API has no GET /categories yet, so this mirrors the
 * seed codes; replace with the endpoint when it exists. Codes are stable; names are display only.
 */
export const OUT_CATEGORIES: CategoryOption[] = [
  { code: 'GROCERIES', name: 'Groceries' },
  { code: 'DINING', name: 'Dining' },
  { code: 'TRANSPORT', name: 'Transport' },
  { code: 'FUEL', name: 'Fuel' },
  { code: 'SHOPPING', name: 'Shopping' },
  { code: 'UTILITIES', name: 'Utilities' },
  { code: 'RENT', name: 'Rent' },
  { code: 'HEALTH', name: 'Health' },
  { code: 'EDUCATION', name: 'Education' },
  { code: 'INSURANCE', name: 'Insurance' },
  { code: 'EMI', name: 'EMI' },
  { code: 'SUBSCRIPTIONS', name: 'Subscriptions' },
  { code: 'ENTERTAINMENT', name: 'Entertainment' },
  { code: 'TRAVEL', name: 'Travel' },
  { code: 'PERSONAL_CARE', name: 'Personal care' },
  { code: 'GIFTS_DONATIONS', name: 'Gifts and donations' },
  { code: 'TAXES_FEES', name: 'Taxes and fees' },
  { code: 'OTHER', name: 'Other' },
];
