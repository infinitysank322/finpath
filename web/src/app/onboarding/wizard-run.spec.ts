import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { USER_A, tokenResponse } from '../auth/auth-testing';
import { AUTH_STORAGE, AuthSessionStore } from '../auth/session.store';
import { API_BASE } from '../expense/expense-api.service';
import { QUEUE_STORAGE } from '../expense/expense-queue.service';
import { memoryStorage } from '../expense/queue-testing';
import { OnboardingComponent } from './onboarding.component';
import { ONBOARDING_STEP_PROVIDERS } from './onboarding.steps';
import { wizardKeyFor } from './wizard-progress';

const STATEMENT = { purpose: 'plan', version: '2026-10-v2', text: 'We keep your plan.' };
const CYCLE = { cycleStart: '2026-10-25', cycleEndExclusive: '2026-11-25', startDay: 25, status: 'OPEN' };

/**
 * FR-01 whole-wizard run (T69), automated: the real OnboardingComponent with the real step registry, every
 * step driven through the DOM, HTTP mocked. It asserts what each step sends. It is NOT a browser run.
 */
describe('FR-01 full wizard run (automated, mocked HTTP)', () => {
  let http: HttpTestingController;
  let storage: ReturnType<typeof memoryStorage>;
  let f: ComponentFixture<OnboardingComponent>;

  const el = () => f.nativeElement as HTMLElement;
  const settle = async () => {
    await f.whenStable();
    await new Promise((r) => setTimeout(r));
    f.detectChanges();
  };
  const click = async (text: string) => {
    Array.from(el().querySelectorAll('button')).find((x) => x.textContent?.trim() === text)!.click();
    await settle();
  };
  const type = async (id: string, value: string) => {
    const i = el().querySelector<HTMLInputElement>('#' + id)!;
    i.value = value;
    i.dispatchEvent(new Event('input'));
    await settle();
  };
  const heading = () => el().querySelector('h1')!.textContent;
  const progress = () => el().querySelector('.progress-text')!.textContent;
  const nextDisabled = () => Array.from(el().querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Next')!.disabled;

  beforeEach(() => {
    storage = memoryStorage();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]), provideHttpClient(), provideHttpClientTesting(),
        { provide: API_BASE, useValue: '/api' },
        { provide: AUTH_STORAGE, useValue: storage },
        { provide: QUEUE_STORAGE, useValue: memoryStorage() },
        ...ONBOARDING_STEP_PROVIDERS,
      ],
    });
    TestBed.inject(AuthSessionStore).save(tokenResponse(USER_A, 'r'));
    http = TestBed.inject(HttpTestingController);
    f = TestBed.createComponent(OnboardingComponent);
    f.detectChanges();
  });
  afterEach(() => {
    http.verify();
    TestBed.resetTestingModule();
  });

  it('walks consent, cycle day, income, bills, goals, assets and done, posting the right paise at each step', async () => {
    expect(progress()).toBe('Step 1 of 7');

    // 1. consent: Next stays disabled until the server says given
    http.expectOne('/api/me/consent').flush({ statement: STATEMENT, given: false });
    await settle();
    expect(nextDisabled()).toBeTrue();
    el().querySelector<HTMLInputElement>('input[type=checkbox]')!.click();
    await settle();
    await click('Give consent');
    const consentPost = http.expectOne((r) => r.method === 'POST' && r.url === '/api/me/consent');
    expect(consentPost.request.body).toEqual({ purpose: 'plan', statementVersion: '2026-10-v2' });
    consentPost.flush({}, { status: 201, statusText: 'Created' });
    await settle();
    http.expectOne('/api/me/consent').flush({ statement: STATEMENT, given: true });
    await settle();
    await click('Next');

    // 2. cycle day: change 25 -> 26, saved to the server before Next opens
    expect(progress()).toBe('Step 2 of 7');
    http.expectOne('/api/plan-cycles/current').flush(CYCLE);
    await settle();
    await type('cycle-day', '26');
    expect(nextDisabled()).toBeTrue();
    await click('Save day');
    const day = http.expectOne('/api/plan-cycles/start-day');
    expect(day.request.method).toBe('PUT');
    expect(day.request.body).toEqual({ startDay: 26 });
    day.flush({ ...CYCLE, startDay: 26, cycleStart: '2026-10-26' });
    await settle();
    await click('Next');

    // 3. income: Next is gated on at least one income source
    expect(progress()).toBe('Step 3 of 7');
    http.expectOne('/api/income-sources').flush([]);
    http.expectOne('/api/income-sources/cycle-total').flush({ cycleStart: '2026-10-26', cycleEndExclusive: '2026-11-26', totalPaise: 0, items: [] });
    await settle();
    expect(nextDisabled()).toBeTrue();
    await type('inc-amt', '87,654');
    await click('Add income');
    const inc = http.expectOne('/api/income-sources');
    expect(inc.request.method).toBe('POST');
    expect(inc.request.body).toEqual(jasmine.objectContaining({ type: 'SALARY', frequency: 'MONTHLY', amountPaise: 8765400, endDate: null }));
    inc.flush({});
    const incomeRow = { id: 'i1', type: 'SALARY', frequency: 'MONTHLY', amountPaise: 8765400, startDate: '2026-10-26', endDate: null, countsInCurrentCycle: true };
    http.expectOne('/api/income-sources').flush([incomeRow]);
    http.expectOne('/api/income-sources/cycle-total').flush({ cycleStart: '2026-10-26', cycleEndExclusive: '2026-11-26', totalPaise: 8765400, items: [] });
    await settle();
    expect(nextDisabled()).toBeFalse();
    await click('Next');

    // 4. fixed expenses (optional)
    expect(progress()).toBe('Step 4 of 7');
    http.expectOne('/api/recurring-templates').flush([]);
    await settle();
    await type('fx-amt', '1234.50');
    await click('Add bill');
    const bill = http.expectOne('/api/recurring-templates');
    expect(bill.request.body).toEqual(jasmine.objectContaining({ categoryCode: 'RENT', amountPaise: 123450, frequency: 'MONTHLY' }));
    bill.flush({});
    http.expectOne('/api/recurring-templates').flush([]);
    await settle();
    await click('Next');

    // 5. goals (optional)
    expect(progress()).toBe('Step 5 of 7');
    http.expectOne('/api/goals').flush([]);
    await settle();
    await type('goal-target', '5,00,000');
    await type('goal-date', '2030-03-25');
    await click('Add goal');
    const goal = http.expectOne('/api/goals');
    expect(goal.request.body).toEqual({ type: 'emergency', targetAmountPaise: 50000000, targetDate: '2030-03-25', currentAmountPaise: 0, priority: 5 });
    goal.flush({});
    http.expectOne('/api/goals').flush([]);
    await settle();
    await click('Next');

    // 6. assets (optional): explicit EPF balance, then one more holding
    expect(progress()).toBe('Step 6 of 7');
    expect(heading()).toContain('already have invested');
    http.expectOne('/api/holdings').flush([]);
    await settle();
    expect(el().querySelector<HTMLInputElement>('#epf-balance')!.value).toBe('0');
    expect(nextDisabled()).toBeFalse(); // skippable
    await type('epf-balance', '4,50,000');
    await click('Save EPF balance');
    const epf = http.expectOne('/api/holdings');
    expect(epf.request.body).toEqual({ assetClass: 'epf', name: 'EPF', investedPaise: 0, currentValuePaise: 45000000, monthlyContributionPaise: 0 });
    epf.flush({});
    http.expectOne('/api/holdings').flush([{ id: 'e1', ...epf.request.body }]);
    await settle();
    await type('asset-name', 'Index fund');
    await type('asset-value', '2,50,000.75');
    await click('Add holding');
    const mf = http.expectOne('/api/holdings');
    expect(mf.request.body).toEqual({ assetClass: 'mf', name: 'Index fund', investedPaise: 0, currentValuePaise: 25000075, monthlyContributionPaise: 0 });
    mf.flush({});
    http.expectOne('/api/holdings').flush([]);
    await settle();
    await click('Next');

    // 7. done: Finish re-asks the server, clears the saved position and goes home
    expect(progress()).toBe('Step 7 of 7');
    expect(heading()).toContain("You're set");
    expect(JSON.parse(storage.data[wizardKeyFor(USER_A)])).toEqual({ id: 'done', index: 6 });
    const router = TestBed.inject(Router);
    spyOn(router, 'navigateByUrl').and.resolveTo(true);
    await click('Finish');
    http.expectOne('/api/me/consent').flush({ statement: STATEMENT, given: true });
    await settle();
    expect(router.navigateByUrl).toHaveBeenCalledWith('/home');
    expect(storage.data[wizardKeyFor(USER_A)]).toBeUndefined();
  });

  it('lets the user skip the optional steps (bills, goals, assets) without sending anything', async () => {
    http.expectOne('/api/me/consent').flush({ statement: STATEMENT, given: true });
    await settle();
    await click('Next');
    http.expectOne('/api/plan-cycles/current').flush(CYCLE);
    await settle();
    await click('Next');
    http.expectOne('/api/income-sources').flush([{ id: 'i1', type: 'SALARY', frequency: 'MONTHLY', amountPaise: 100, startDate: '2026-10-25', endDate: null, countsInCurrentCycle: true }]);
    http.expectOne('/api/income-sources/cycle-total').flush({ cycleStart: '2026-10-25', cycleEndExclusive: '2026-11-25', totalPaise: 100, items: [] });
    await settle();
    await click('Next');
    http.expectOne('/api/recurring-templates').flush([]);
    await settle();
    await click('Next');
    http.expectOne('/api/goals').flush([]);
    await settle();
    await click('Next');
    http.expectOne('/api/holdings').flush([]);
    await settle();
    await click('Next');
    expect(progress()).toBe('Step 7 of 7');
    http.expectNone((r) => r.method !== 'GET');
  });
});
