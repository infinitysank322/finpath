import { HttpErrorResponse, provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { Type } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap, provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { AllocationApiService } from './allocation/allocation-api.service';
import { AllocationComponent } from './allocation/allocation.component';
import { API_BASE } from './api-base';
import { BudgetApiService } from './budget/budget-api.service';
import { BudgetComponent } from './budget/budget.component';
import { ChecklistApiService } from './checklist/checklist-api.service';
import { ChecklistComponent } from './checklist/checklist.component';
import { DashboardApiService } from './dashboard/dashboard-api.service';
import { HomeComponent } from './dashboard/home.component';
import { DebtHubComponent } from './debt/debt-hub.component';
import { EmiGuardComponent } from './debt/emi-guard.component';
import { GuardApiService } from './debt/guard-api.service';
import { LoanDetailComponent } from './debt/loan-detail.component';
import { LoanInterestApiService } from './debt/loan-interest-api.service';
import { LoanInterestComponent } from './debt/loan-interest.component';
import { LoanListComponent } from './debt/loan-list.component';
import { DETAIL_EMI, SAMPLE_EMI_TOTAL_PAISE, SAMPLE_VIEWS } from './debt/loan.fixture';
import { PayoffApiService } from './debt/payoff-api.service';
import { PayoffComponent } from './debt/payoff.component';
import { PrepayApiService } from './debt/prepay-api.service';
import { PrepayComponent } from './debt/prepay.component';
import { SinkingFundApiService } from './debt/sinking-fund-api.service';
import { SinkingFundComponent } from './debt/sinking-fund.component';
import { FiApiService } from './fi/fi-api.service';
import { FiPlannerComponent } from './fi/fi-planner.component';
import { HoldingApiService } from './investments/holding-api.service';
import { InvestmentsComponent } from './investments/investments.component';
import { GoalApiService } from './goals/goal-api.service';
import { GoalsComponent } from './goals/goals.component';
import { AssetsStepComponent } from './onboarding/assets-step.component';
import { ConsentStepComponent } from './onboarding/consent-step.component';
import { CycleDayStepComponent } from './onboarding/cycle-day-step.component';
import { DebtStepComponent } from './onboarding/debt-step.component';
import { DoneStepComponent } from './onboarding/done-step.component';
import { FixedExpensesStepComponent } from './onboarding/fixed-expenses-step.component';
import { GoalStepComponent } from './onboarding/goal-step.component';
import { IncomeStepComponent } from './onboarding/income-step.component';
import { OnboardingComponent } from './onboarding/onboarding.component';
import { SHELL_STEP_PROVIDERS } from './onboarding/onboarding.steps';
import { IncomeComponent } from './plan/income.component';
import { PlanApiService } from './plan/plan-api.service';
import { RecurringComponent } from './plan/recurring.component';
import { expectFitsPhone, expectTarget44 } from './ui/ui-testing';

/**
 * T06c: every screen rebuilt on the design system fits a 380 px phone in dark and light with no horizontal scroll,
 * and every control on it is at least 44 x 44. One test per screen; each is rendered with data and, where the screen
 * has one, with its form / sheet open, so the widest state is the one measured. All figures below are test fixtures.
 */

const line = (categoryCode: string, level: string, usedFraction: string | null, spentPaise: number, remainingPaise: number) => ({
  categoryCode, budgetPaise: 1000000, spentPaise, remainingPaise, usedFraction, level, lastAlertLevel: 'OK',
});
const budgetApi = {
  evaluate: () => of({ events: [{ categoryCode: 'TRANSPORT', level: 'WARN' }] }),
  status: () =>
    of({
      cycleStart: '2026-10-25',
      cycleEndExclusive: '2026-11-25',
      lines: [line('FOOD', 'OK', '0.1000', 100000, 900000), line('TRANSPORT', 'WARN', '0.8500', 850000, 150000), line('SHOPPING', 'BREACH', '1.2500', 1250000, -250000)],
    }),
  put: () => of({}),
  delete: () => of(undefined),
};

const salary = { id: 'i1', type: 'SALARY', frequency: 'MONTHLY', amountPaise: 10000000, startDate: '2026-01-01', endDate: null, countsInCurrentCycle: true };
const bill = {
  id: 't1', categoryCode: 'INSURANCE', amountPaise: 600000, frequency: 'QUARTERLY', mode: 'UPI', tag: null, startDate: '2026-10-25', endDate: null,
  perCycleSpread: [{ index: 0, amountPaise: 200000 }, { index: 1, amountPaise: 200000 }, { index: 2, amountPaise: 200000 }], currentInstalmentPaise: 200000,
};
const planApi = {
  incomeList: () => of([salary, { ...salary, id: 'i2', type: 'BONUS', frequency: 'ONE_OFF', amountPaise: 1400000, countsInCurrentCycle: false }]),
  incomeCycleTotal: () => of({ cycleStart: '2026-10-25', cycleEndExclusive: '2026-11-25', totalPaise: 12700000, items: [] }),
  templateList: () => of([bill]),
  materialise: () => of({ cycleStart: '2026-10-25', cycleEndExclusive: '2026-11-25', items: [], createdCount: 1, postedPaise: 200000 }),
};

const cls = (assetClass: string, v: number) => ({ assetClass, investedPaise: 500000, currentValuePaise: v, monthlyContributionPaise: 300000, count: v > 0 ? 1 : 0 });
const holdingApi = {
  list: () => of([{ id: 'h1', assetClass: 'epf', name: 'Employer EPF', investedPaise: 0, currentValuePaise: 1250000, monthlyContributionPaise: 300000 }]),
  aggregate: () => of({ classes: [cls('epf', 1250000), cls('ppf', 0), cls('nps', 0), cls('mf', 0), cls('stocks', 0), cls('fd', 0), cls('gold', 750000)], total: cls('total', 2000000) }),
};

const fiPlan = {
  yearsToFi: 15, inflatedMonthlyExpensePaise: 1, inflatedMonthlyPassivePaise: 0, grossCorpusPaise: 384000000, netCorpusPaise: 276000000,
  openingBalancePaise: 0, monthlySipPaise: 0,
  bands: [
    { band: 'LOW', annualReturn: '0.0825', requiredFirstMonthlyPaise: 9000000, projectedSavingsAtHorizonPaise: 200000000, fiYears: 18, fiDate: '2044-10-23' },
    { band: 'BASE', annualReturn: '0.10125', requiredFirstMonthlyPaise: 7000000, projectedSavingsAtHorizonPaise: 276000000, fiYears: 15, fiDate: '2041-10-23' },
    { band: 'HIGH', annualReturn: '0.12', requiredFirstMonthlyPaise: 5000000, projectedSavingsAtHorizonPaise: 350000000, fiYears: null, fiDate: null },
  ],
  assumptions: { inflation: '0.06', equityReturn: '0.12', debtReturnLow: '0.07', debtReturnHigh: '0.0825', fiMultiplier: 25, stepUp: '0.10' },
  disclaimer: 'Educational projection, not investment advice. Returns are assumptions, not guarantees.',
};

const allocation = {
  cycleStart: '2026-10-25', surplusPaise: 6000000, shortfallPaise: 0, allocatedPaise: 6000000, prepayShare: '0.3',
  lines: [
    { step: 'EMERGENCY_FUND_ONE_MONTH', ref: null, amountPaise: 1750000 },
    { step: 'HIGH_INTEREST_DEBT', ref: 'L1', amountPaise: 1234500 },
    { step: 'LONG_TERM_SIP', ref: null, amountPaise: 3265500 },
  ],
};

const payoffPlan = {
  strategy: 'avalanche',
  order: [
    { loanId: 'a', lender: 'Bank A', outstandingPaise: 4400003, annualRate: '18', monthlyPaymentPaise: 77701, payoffCycle: 0, payoffCycleDates: { start: '2026-11-25', endExclusive: '2026-12-25' } },
    { loanId: 'b', lender: 'Bank B', outstandingPaise: 1400000007, annualRate: '9', monthlyPaymentPaise: 123457, payoffCycle: 11, payoffCycleDates: { start: '2027-10-25', endExclusive: '2027-11-25' } },
  ],
  totalInterestPaise: 12345601, totalPaidPaise: 1700000009, cycles: 12,
};
const rollOff = {
  redirectPct: '100',
  steps: [{ cycleId: '2026-11-25', cycleEndExclusive: '2026-12-25', cycleIndex: 1, loanIds: ['a'], freedPaise: 77701, cumulativeFreedPaise: 77701, redirectedPaise: 77701, cumulativeRedirectedPaise: 77701 }],
};

const inst = (number: number, interestPaise: number, principalPaise: number) => ({
  number, openingPaise: 0, interestPaise, principalPaise, paymentPaise: interestPaise + principalPaise, closingPaise: 0,
});
const interestDetail = {
  id: 'L1', lender: 'Bank Q', type: 'PERSONAL', emiUsedPaise: 123457, emiDerived: true, effectiveAnnualRate: '11.5',
  interestRemainingPaise: 1234583, totalRemainingPayablePaise: 11100050, instalmentsRemaining: 3, closed: false, pastDue: true, balloonFinalPayment: true,
  instalments: [inst(1, 700001, 3000017), inst(2, 400003, 3300013), inst(3, 134579, 3565437)],
};

const prepayView = {
  loanId: 'L1',
  assumptions: { mode: 'lump_sum', annualRate: '8.35', principalOutstandingPaise: 123456789, currentEmiPaise: 4003201, tenureMonths: 297, firstInstalmentFrom: '2026-10-23', note: 'No fees modelled.' },
  newEmiPaise: 4003201, baselineInstalments: 297, newInstalments: 156, instalmentsSaved: 141,
  baselineInterestPaise: 9876543210, newInterestPaise: 5000000001, interestSavedPaise: 4876543209,
  baselinePayoffCycle: { start: '2051-06-25', endExclusive: '2051-07-25' }, newPayoffCycle: { start: '2039-09-25', endExclusive: '2039-10-25' },
};
const fund = {
  loanId: 'L1', cyclesRemaining: 10, outstandingPaise: 12345678, setAsidePerCyclePaise: 1234568, finalCycleAmountPaise: 1234558,
  expectedSavedByNowPaise: 4000001, savedDeltaPaise: -3000001, catchUpPaise: 3000001, status: 'BEHIND', behindSchedule: true,
};
const guardView = { ratio: '0.4987000000', status: 'WARN', headroomPaise: 1300, totalEmiPaise: 4987000, monthlyIncomePaise: 10000000 };

const route = (extra: object) => ({ provide: ActivatedRoute, useValue: extra });
const idRoute = route({ paramMap: of(convertToParamMap({ id: 'L1' })), snapshot: { paramMap: new Map([['id', 'L1']]) } });

/** Every control that is on screen must be a 44 x 44 target. */
function expectTargets(root: HTMLElement, name: string, needControls = true): void {
  const controls = root.querySelectorAll('button, a[href], select, input:not([type=hidden]), summary');
  if (needControls) expect(controls.length).withContext(`${name} has controls`).toBeGreaterThan(0);
  controls.forEach((el, i) => {
    if (el.getClientRects().length === 0 || el.closest('.sr-only')) return;
    expectTarget44(el instanceof HTMLInputElement && el.type === 'checkbox' ? (el.closest('label') ?? el) : el, `${name} control ${i} (${el.tagName.toLowerCase()} ${(el.textContent ?? '').trim().slice(0, 24)})`);
  });
}

describe('every rebuilt screen at 380 px (dark and light) with 44 px targets', () => {
  function render<T>(component: Type<T>, providers: unknown[], prepare?: (f: ComponentFixture<T>) => void): HTMLElement {
    TestBed.configureTestingModule({
      imports: [component],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: API_BASE, useValue: '/api' }, ...(providers as [])],
    });
    const f = TestBed.createComponent(component);
    f.detectChanges();
    prepare?.(f);
    f.detectChanges();
    return f.nativeElement as HTMLElement;
  }
  const check = (el: HTMLElement, name: string, needControls = true) => {
    document.body.appendChild(el);
    try {
      expectTargets(el, name, needControls);
    } finally {
      el.remove();
    }
    expectFitsPhone(el, name);
  };

  it('budget, with the edit sheet open', () => {
    const el = render(BudgetComponent, [{ provide: BudgetApiService, useValue: budgetApi }], (f) => {
      f.detectChanges();
      f.componentInstance.openEdit(f.componentInstance.status()!.lines[0]);
    });
    check(el, 'budget');
  });

  it('home cycle ring, with a warning level', () => {
    const dash = {
      asOf: '2026-10-25',
      cycle: { start: '2026-10-25', endExclusive: '2026-11-25', lengthDays: 31, daysElapsed: 12, daysLeft: 19 },
      budget: { status: 'OK', budgetPaise: 1234500, spentPaise: 1050000, remainingPaise: 184500, usedFraction: '0.8506', level: 'WARN', safeToSpendPerDayPaise: 9700 },
    };
    check(render(HomeComponent, [{ provide: DashboardApiService, useValue: { get: () => of(dash) } }]), 'home ring', false);
  });

  it('home, failed load shows a 44 px retry', () => {
    const failing = render(HomeComponent, [{ provide: DashboardApiService, useValue: { get: () => throwError(() => new HttpErrorResponse({ status: 500 })) } }]);
    expect(failing.querySelector('[data-f=failed]')).not.toBeNull();
    check(failing, 'home failed');
  });

  it('income, with the form open', () => {
    const el = render(IncomeComponent, [{ provide: PlanApiService, useValue: planApi }], (f) => f.componentInstance.openAdd());
    check(el, 'income');
  });

  it('recurring bills, with the form open', () => {
    const el = render(RecurringComponent, [{ provide: PlanApiService, useValue: planApi }], (f) => {
      f.componentInstance.openAdd();
    });
    check(el, 'recurring');
  });

  it('investments, with the form open', () => {
    const el = render(InvestmentsComponent, [{ provide: HoldingApiService, useValue: holdingApi }], (f) => f.componentInstance.openAdd());
    check(el, 'investments');
  });

  it('FI planner, with a projection', () => {
    const el = render(FiPlannerComponent, [{ provide: FiApiService, useValue: { plan: () => of(fiPlan) } }], (f) => {
      f.componentInstance.expense.set('50000');
      f.componentInstance.years.set('15');
      f.componentInstance.submit();
    });
    expect(el.querySelector('table')).not.toBeNull();
    check(el, 'fi planner');
  });

  it('cycle allocation, with the unplanned-spend check answered', () => {
    const api = {
      current: () => of(allocation),
      run: () => of(allocation),
      rebalance: () =>
        of({ absorbed: [{ step: 'LONG_TERM_SIP', ref: null, amountPaise: 1750000 }], primaryAbsorbingStep: 'LONG_TERM_SIP', uncoveredPaise: 0, surplusAfterPaise: 4500000, lines: [], safeToSpendBeforePaise: 900000, safeToSpendAfterPaise: 700000 }),
    };
    const el = render(AllocationComponent, [{ provide: AllocationApiService, useValue: api }], (f) => {
      f.componentInstance.spend.set('17500');
      f.componentInstance.check();
    });
    expect(el.querySelector('[data-f=absorber]')).not.toBeNull();
    check(el, 'allocation');
  });

  it('debt hub', () => {
    check(render(DebtHubComponent, []), 'debt hub');
  });

  it('loan list, with the add-loan form open', () => {
    TestBed.configureTestingModule({
      imports: [LoanListComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: API_BASE, useValue: '/api' }],
    });
    const http = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(LoanListComponent);
    f.detectChanges();
    http.expectOne('/api/loans').flush(SAMPLE_VIEWS);
    http.expectOne('/api/loans/debt-trap-guard').flush({ ratio: '0.4', status: 'OK', headroomPaise: 0, totalEmiPaise: SAMPLE_EMI_TOTAL_PAISE, monthlyIncomePaise: 1_234_500 });
    f.detectChanges();
    const el = f.nativeElement as HTMLElement;
    el.querySelector('details')!.open = true;
    f.detectChanges();
    check(el, 'loan list');
  });

  it('loan detail', () => {
    TestBed.configureTestingModule({
      imports: [LoanDetailComponent],
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: API_BASE, useValue: '/api' }, route({ paramMap: of(convertToParamMap({ id: 'abc' })) })],
    });
    const http = TestBed.inject(HttpTestingController);
    const f = TestBed.createComponent(LoanDetailComponent);
    f.detectChanges();
    http.expectOne('/api/loans/abc/detail').flush(DETAIL_EMI);
    f.detectChanges();
    check(f.nativeElement as HTMLElement, 'loan detail');
  });

  it('payoff timeline', () => {
    const api = { payoffPlan: () => of(payoffPlan), rollOff: () => of(rollOff) };
    check(render(PayoffComponent, [{ provide: PayoffApiService, useValue: api }]), 'payoff');
  });

  it('principal vs interest', () => {
    const el = render(LoanInterestComponent, [{ provide: LoanInterestApiService, useValue: { detail: () => of(interestDetail) } }, idRoute]);
    check(el, 'principal vs interest');
  });

  it('prepayment simulator, with a result', () => {
    const el = render(PrepayComponent, [{ provide: PrepayApiService, useValue: { simulate: () => of(prepayView) } }, idRoute], (f) => {
      f.componentInstance.lump.set('100000');
      f.componentInstance.run();
    });
    expect(el.querySelector('[data-r=saved]')).not.toBeNull();
    check(el, 'prepay');
  });

  it('sinking fund, with a result', () => {
    const el = render(SinkingFundComponent, [{ provide: SinkingFundApiService, useValue: { status: () => of(fund) } }, idRoute], (f) => {
      f.componentInstance.saved.set('10000');
      f.componentInstance.load();
    });
    expect(el.querySelector('[data-f=state]')).not.toBeNull();
    check(el, 'sinking fund');
  });

  it('EMI to income', () => {
    check(render(EmiGuardComponent, [{ provide: GuardApiService, useValue: { current: () => of(guardView) } }]), 'emi guard');
  });

  it('goals, with the form open', () => {
    const g = (id: string, type: string, cur: number) => ({ id, type, targetAmountPaise: 500000, targetDate: '2031-03-25', currentAmountPaise: cur, priority: 3 });
    const goalApi = {
      list: () => of([g('g1', 'home', 125000), g('g2', 'travel', 0)]),
      status: (id: string) =>
        of({ cyclesRemaining: 53, shortfallPaise: 0, monthlyNeedPaise: 625000, plannedMonthlyPaise: 0, status: id === 'g1' ? 'BEHIND' : 'PAST_DUE', onTrack: false, instrumentClass: 'SAFE_ONLY' }),
    };
    const alloc = { current: () => of({ cycleStart: '2026-10-25', surplusPaise: 0, shortfallPaise: 0, allocatedPaise: 0, prepayShare: null, lines: [] }) };
    const el = render(GoalsComponent, [{ provide: GoalApiService, useValue: goalApi }, { provide: AllocationApiService, useValue: alloc }], (f) => f.componentInstance.openAdd());
    check(el, 'goals');
  });

  it('checklist with heatmap', () => {
    const it1 = (id: string, done: boolean) => ({ id, type: 'EMI', sourceRef: id, label: 'Loan ' + id, amountPaise: 1234500, done, doneAt: null });
    const api = {
      current: () => of({ cycleStart: '2026-10-25', items: [it1('a', true), it1('b', false)], doneCount: 1, totalCount: 2 }),
      streak: () => of({ streak: 3, history: Array.from({ length: 12 }, (_, i) => ({ cycleStart: `2026-${String(i + 1).padStart(2, '0')}-25`, done: i % 3 !== 0 })) }),
    };
    const el = render(ChecklistComponent, [{ provide: ChecklistApiService, useValue: api }]);
    expect(el.querySelectorAll('[data-f=cell]').length).toBe(12);
    check(el, 'checklist');
  });

  describe('onboarding', () => {
    const steps: [string, Type<unknown>][] = [
      ['consent', ConsentStepComponent], ['cycle day', CycleDayStepComponent], ['income', IncomeStepComponent],
      ['fixed expenses', FixedExpensesStepComponent], ['debts', DebtStepComponent], ['goals', GoalStepComponent],
      ['assets', AssetsStepComponent], ['done', DoneStepComponent],
    ];
    for (const [name, comp] of steps) {
      it(`step: ${name}`, () => check(render(comp, []), `onboarding ${name}`, name !== 'consent' && name !== 'done'));
    }
    it('shell with the first step', () => {
      check(render(OnboardingComponent, SHELL_STEP_PROVIDERS), 'onboarding shell');
    });
  });
});
