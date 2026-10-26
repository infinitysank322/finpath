import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { API_BASE } from '../expense/expense-api.service';
import { CycleDayStepComponent } from './cycle-day-step.component';
import { FixedExpensesStepComponent } from './fixed-expenses-step.component';
import { GoalStepComponent } from './goal-step.component';
import { IncomeStepComponent } from './income-step.component';
import { PlanStepsState } from './plan-steps.state';

const CYCLE = { cycleStart: '2026-10-25', cycleEndExclusive: '2026-11-25', startDay: 25, status: 'OPEN' };

describe('onboarding plan steps', () => {
  let http: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideRouter([]), provideHttpClient(), provideHttpClientTesting(), { provide: API_BASE, useValue: '/api' }],
    });
    http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => http.verify());

  const el = (f: ComponentFixture<unknown>) => f.nativeElement as HTMLElement;
  const type = (f: ComponentFixture<unknown>, id: string, value: string) => {
    const i = el(f).querySelector<HTMLInputElement>('#' + id)!;
    i.value = value;
    i.dispatchEvent(new Event('input'));
    f.detectChanges();
  };
  const button = (f: ComponentFixture<unknown>, text: string) =>
    Array.from(el(f).querySelectorAll('button')).find((b) => b.textContent?.trim() === text)!;

  describe('cycle day', () => {
    it('defaults to 25 from the server and is ready once loaded', () => {
      const f = TestBed.createComponent(CycleDayStepComponent);
      f.detectChanges();
      http.expectOne('/api/plan-cycles/current').flush(CYCLE);
      f.detectChanges();
      expect(el(f).querySelector<HTMLInputElement>('#cycle-day')!.value).toBe('25');
      expect(TestBed.inject(PlanStepsState).cycleDaySaved()).toBeTrue();
    });

    it('is not ready after an edit until the new day is saved', () => {
      const f = TestBed.createComponent(CycleDayStepComponent);
      f.detectChanges();
      http.expectOne('/api/plan-cycles/current').flush(CYCLE);
      f.detectChanges();
      type(f, 'cycle-day', '1');
      expect(TestBed.inject(PlanStepsState).cycleDaySaved()).toBeFalse();
      button(f, 'Save day').click();
      const req = http.expectOne('/api/plan-cycles/start-day');
      expect(req.request.body).toEqual({ startDay: 1 });
      req.flush({ ...CYCLE, startDay: 1, cycleStart: '2026-10-01' });
      expect(TestBed.inject(PlanStepsState).cycleDaySaved()).toBeTrue();
    });

    it('rejects a day outside 1 to 31', () => {
      const f = TestBed.createComponent(CycleDayStepComponent);
      f.detectChanges();
      http.expectOne('/api/plan-cycles/current').flush(CYCLE);
      type(f, 'cycle-day', '32');
      expect(button(f, 'Save day').disabled).toBeTrue();
      expect(TestBed.inject(PlanStepsState).cycleDaySaved()).toBeFalse();
    });
  });

  describe('income', () => {
    const row = { id: 'i1', type: 'SALARY', frequency: 'MONTHLY', amountPaise: 8765400, startDate: '2026-10-25', endDate: null, countsInCurrentCycle: true };
    const total = { cycleStart: '2026-10-25', cycleEndExclusive: '2026-11-25', totalPaise: 8765400, items: [] };

    it('posts paise for a typed rupee amount, then shows the API total', () => {
      const f = TestBed.createComponent(IncomeStepComponent);
      f.detectChanges();
      http.expectOne('/api/income-sources').flush([]);
      http.expectOne('/api/income-sources/cycle-total').flush({ ...total, totalPaise: 0 });
      expect(TestBed.inject(PlanStepsState).incomeReady()).toBeFalse();
      type(f, 'inc-amt', '87,654');
      button(f, 'Add income').click();
      const post = http.expectOne('/api/income-sources');
      expect(post.request.method).toBe('POST');
      expect(post.request.body.amountPaise).toBe(8765400);
      expect(post.request.body.frequency).toBe('MONTHLY');
      post.flush(row);
      http.expectOne('/api/income-sources').flush([row]);
      http.expectOne('/api/income-sources/cycle-total').flush(total);
      f.detectChanges();
      expect(TestBed.inject(PlanStepsState).incomeReady()).toBeTrue();
      expect(el(f).querySelector('[role=status]')?.textContent).toContain('87,654');
    });

    it('will not add an empty or malformed amount', () => {
      const f = TestBed.createComponent(IncomeStepComponent);
      f.detectChanges();
      http.expectOne('/api/income-sources').flush([]);
      http.expectOne('/api/income-sources/cycle-total').flush({ ...total, totalPaise: 0 });
      type(f, 'inc-amt', 'abc');
      expect(button(f, 'Add income').disabled).toBeTrue();
    });
  });

  describe('fixed expenses', () => {
    it('creates a template with the bill amount in paise and lists it', () => {
      const f = TestBed.createComponent(FixedExpensesStepComponent);
      f.detectChanges();
      http.expectOne('/api/recurring-templates').flush([]);
      type(f, 'fx-amt', '1234.50');
      button(f, 'Add bill').click();
      const post = http.expectOne('/api/recurring-templates');
      expect(post.request.body).toEqual(jasmine.objectContaining({ categoryCode: 'RENT', amountPaise: 123450, frequency: 'MONTHLY' }));
      post.flush({});
      http.expectOne('/api/recurring-templates').flush([
        { id: 't1', categoryCode: 'RENT', amountPaise: 123450, frequency: 'MONTHLY', mode: 'UPI', tag: 'NEED', startDate: '2026-10-25', endDate: null, perCycleSpread: [], currentInstalmentPaise: 123450 },
      ]);
      f.detectChanges();
      expect(el(f).querySelector('.rows')?.textContent).toContain('Rent');
    });
  });

  describe('goals', () => {
    it('posts paise and a date, sets the state count, and lists the goal', () => {
      const f = TestBed.createComponent(GoalStepComponent);
      f.detectChanges();
      http.expectOne('/api/goals').flush([]);
      expect(button(f, 'Add goal').disabled).toBeTrue();
      type(f, 'goal-target', '5,00,000');
      type(f, 'goal-date', '2030-03-25');
      button(f, 'Add goal').click();
      const post = http.expectOne('/api/goals');
      expect(post.request.method).toBe('POST');
      expect(post.request.body).toEqual({ type: 'emergency', targetAmountPaise: 50000000, targetDate: '2030-03-25', currentAmountPaise: 0, priority: 5 });
      post.flush({});
      const g = { id: 'g1', type: 'emergency', targetAmountPaise: 50000000, targetDate: '2030-03-25', currentAmountPaise: 0, priority: 5 };
      http.expectOne('/api/goals').flush([g]);
      f.detectChanges();
      expect(TestBed.inject(PlanStepsState).goalCount()).toBe(1);
      expect(el(f).querySelector('.rows')?.textContent).toContain('Emergency fund');
      expect(el(f).querySelector('.rows')?.textContent).toContain('₹5,00,000');
    });

    it('will not add without a valid amount', () => {
      const f = TestBed.createComponent(GoalStepComponent);
      f.detectChanges();
      http.expectOne('/api/goals').flush([]);
      type(f, 'goal-target', '0');
      type(f, 'goal-date', '2030-03-25');
      expect(button(f, 'Add goal').disabled).toBeTrue();
    });
  });
});
