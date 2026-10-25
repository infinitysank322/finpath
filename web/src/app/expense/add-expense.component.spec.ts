import { HttpResponse, provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { AuthService, SIGNIN_STORAGE } from '../auth/auth.service';
import { AUTH_STORAGE } from '../auth/session.store';
import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { AddExpenseComponent, DRAFT_KEY, todayIst } from './add-expense.component';
import { ExpenseApiService } from './expense-api.service';
import { ExpenseQueueService, QUEUE_OWNER, QUEUE_STORAGE } from './expense-queue.service';
import { memoryStorage } from './queue-testing';
import { ExpenseInput, ExpenseView, OUT_CATEGORIES } from './expense.models';

class FakeApi {
  calls: { input: ExpenseInput; key: string }[] = [];
  categories() {
    return OUT_CATEGORIES;
  }
  failWith: number | null = null;
  create(input: ExpenseInput, key: string): Observable<HttpResponse<ExpenseView>> {
    this.calls.push({ input, key });
    if (this.failWith !== null) return throwError(() => new HttpErrorResponse({ status: this.failWith! }));
    return of(new HttpResponse<ExpenseView>({ status: 201 }));
  }
}

describe('AddExpenseComponent', () => {
  let api: FakeApi;
  let root: HTMLElement;
  let fixture: ReturnType<typeof TestBed.createComponent<AddExpenseComponent>>;
  let taps: number;
  let queue: ExpenseQueueService;

  /** Every user interaction goes through here, so `taps` is the literal count of clicks. */
  const tap = (selector: string) => {
    const el = root.querySelector(selector) as HTMLElement;
    expect(el).withContext(selector).not.toBeNull();
    el.click();
    taps++;
    fixture.detectChanges();
  };
  const tapKey = (k: string) => tap(`.keypad button[aria-label="${k === '.' ? 'Decimal point' : 'Digit ' + k}"]`);

  let owner: string | null;
  let signin: ReturnType<typeof memoryStorage>;

  beforeEach(() => {
    owner = 'user-1';
    signin = memoryStorage();
    api = new FakeApi();
    TestBed.configureTestingModule({
      imports: [AddExpenseComponent],
      providers: [
        { provide: ExpenseApiService, useValue: api },
        { provide: QUEUE_STORAGE, useValue: memoryStorage() },
        { provide: QUEUE_OWNER, useValue: () => owner },
        { provide: SIGNIN_STORAGE, useValue: signin },
        { provide: AUTH_STORAGE, useValue: memoryStorage() },
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
      ],
    });
    queue = TestBed.inject(ExpenseQueueService);
    document.documentElement.dataset['theme'] = 'dark';
    fixture = TestBed.createComponent(AddExpenseComponent);
    root = fixture.nativeElement;
    fixture.detectChanges();
    taps = 0;
  });
  afterEach(() => delete document.documentElement.dataset['theme']);

  /**
   * Tap counting (T42 "4 taps"): the plan's four steps are amount, category, mode, save. The amount step is a
   * keypad sequence, so it costs one tap per digit. A one-digit amount is therefore exactly 4 literal taps; a
   * three-digit amount is 4 steps but 6 literal taps. No defaults are preselected for category or mode (a
   * guessed default would write wrong data); date defaults to today (IST) and tag/note are optional, 0 taps.
   */
  it('completes amount -> category -> mode -> save in 4 literal taps (one-digit amount) under 10 s', () => {
    const t0 = performance.now();
    tapKey('5');
    tap('[aria-label="Category Groceries"]');
    tap('[aria-label="Payment mode UPI"]');
    tap('[aria-label="Save expense"]');
    const elapsed = performance.now() - t0;

    expect(taps).toBe(4);
    expect(elapsed).toBeLessThan(10_000);
    expect(api.calls.length).toBe(1);
    expect(api.calls[0].input).toEqual({
      txnDate: todayIst(),
      amountPaise: 500,
      categoryCode: 'GROCERIES',
      mode: 'UPI',
      tag: null,
      note: null,
    });
  });

  it('a multi-digit amount is still four steps: keypad sequence, category, mode, save', () => {
    tapKey('2');
    tapKey('5');
    tapKey('0');
    const amountTaps = taps;
    tap('[aria-label="Category Dining"]');
    tap('[aria-label="Payment mode Cash"]');
    tap('[aria-label="Save expense"]');
    expect(amountTaps).toBe(3);
    expect(taps - amountTaps).toBe(3); // category, mode, save
    expect(api.calls[0].input.amountPaise).toBe(25000);
    expect(Number.isInteger(api.calls[0].input.amountPaise)).toBeTrue();
  });

  it('sends an Idempotency-Key of an API-valid shape, a fresh one for each new entry', () => {
    const run = () => {
      tapKey('7');
      tap('[aria-label="Category Fuel"]');
      tap('[aria-label="Payment mode Card"]');
      tap('[aria-label="Save expense"]');
    };
    run();
    run();
    const [a, b] = api.calls.map((c) => c.key);
    expect(a).toMatch(/^[A-Za-z0-9._:-]{8,64}$/);
    expect(b).toMatch(/^[A-Za-z0-9._:-]{8,64}$/);
    expect(a).not.toBe(b);
  });

  it('Save stays disabled until amount, category and mode are all set', () => {
    const save = () => root.querySelector('[aria-label="Save expense"]') as HTMLButtonElement;
    expect(save().disabled).toBeTrue();
    tapKey('9');
    tap('[aria-label="Category Rent"]');
    expect(save().disabled).toBeTrue();
    tap('[aria-label="Payment mode UPI"]');
    expect(save().disabled).toBeFalse();
  });

  it('every interactive control has an accessible name and groups are labelled', () => {
    const controls = Array.from(root.querySelectorAll('button, input, select, textarea, a')) as HTMLElement[];
    expect(controls.length).toBeGreaterThan(30);
    for (const c of controls) {
      const name = c.getAttribute('aria-label') ?? c.getAttribute('aria-labelledby');
      expect(name).withContext(c.outerHTML).toBeTruthy();
    }
    for (const g of Array.from(root.querySelectorAll('[role=group], [role=radiogroup]'))) {
      expect(g.getAttribute('aria-label') ?? g.getAttribute('aria-labelledby')).withContext(g.outerHTML).toBeTruthy();
    }
    expect(root.querySelector('#note-hint')?.textContent).toContain('Do not enter account, card or ID numbers');
  });

  it('exposes selection state to assistive tech, not colour alone', () => {
    tap('[aria-label="Category Travel"]');
    const travel = root.querySelector('[aria-label="Category Travel"]') as HTMLInputElement;
    expect(travel.type).toBe('radio');
    expect(travel.checked).toBeTrue();
    expect((root.querySelector('[aria-label="Category Dining"]') as HTMLInputElement).checked).toBeFalse();
    expect(travel.name).toBe((root.querySelector('[aria-label="Category Dining"]') as HTMLInputElement).name);
    expect(root.querySelectorAll('fieldset legend').length).toBe(2);
    tap('[aria-label="Tag Need"]');
    expect(root.querySelector('[aria-label="Tag Need"]')?.getAttribute('aria-pressed')).toBe('true');
  });

  it('sends optional tag and trimmed note, and resets after save with a status message', () => {
    tapKey('1');
    tapKey('.');
    tapKey('5');
    tap('[aria-label="Category Dining"]');
    tap('[aria-label="Payment mode UPI"]');
    tap('[aria-label="Tag Want"]');
    const input = root.querySelector('input[type=text]') as HTMLInputElement;
    input.value = '  chai  ';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    tap('[aria-label="Save expense"]');
    expect(api.calls[0].input).toEqual(jasmine.objectContaining({ amountPaise: 150, tag: 'WANT', note: 'chai' }));
    expect(root.querySelector('p.ok')?.textContent).toContain('Saved ₹1.5 under Dining');
    expect(root.querySelector('p.amount .sr-only')?.textContent).toBe('Amount ₹0');
  });

  it('todayIst gives the Indian calendar date', () => {
    expect(todayIst(new Date('2026-10-22T19:00:00Z'))).toBe('2026-10-23');
  });

  const fill = (digit: string) => {
    tapKey(digit);
    tap('[aria-label="Category Dining"]');
    tap('[aria-label="Payment mode UPI"]');
  };
  const errorText = () => root.querySelector('p.error')?.textContent ?? '';
  const status = () => root.querySelector('p.amount') as HTMLElement;

  it('amount status text is exact in paise and has no aria-label/aria-hidden-only content', () => {
    for (const k of ['1', '2', '.', '5']) tapKey(k);
    expect(status().getAttribute('role')).toBe('status');
    expect(status().getAttribute('aria-label')).toBeNull();
    expect(status().querySelector('.sr-only')?.textContent).toBe('Amount ₹12.50');
    for (let i = 0; i < 4; i++) tap('.keypad button[aria-label="Delete last digit"]');
    tapKey('.');
    tapKey('4');
    expect(status().querySelector('.sr-only')?.textContent).toBe('Amount ₹0.40');
  });

  it('visible amount is grouped lakh style while typing and keeps the typed fraction', () => {
    for (const k of '1234567') tapKey(k);
    expect(status().querySelector('[aria-hidden]')?.textContent).toBe('₹12,34,567');
    tapKey('.');
    expect(status().querySelector('[aria-hidden]')?.textContent).toBe('₹12,34,567.');
    tapKey('5');
    expect(status().querySelector('[aria-hidden]')?.textContent).toBe('₹12,34,567.5');
  });

  it('reuses the key for an identical retry after a failure, but not after any field changes', () => {
    api.failWith = 500;
    fill('5');
    tap('[aria-label="Save expense"]');
    tap('[aria-label="Save expense"]');
    expect(api.calls[1].key).toBe(api.calls[0].key);
    tap('[aria-label="Tag Need"]');
    tap('[aria-label="Save expense"]');
    expect(api.calls[2].key).not.toBe(api.calls[0].key);
    // changing a field and changing it back is an identical payload only if it matches the failed one
    tap('[aria-label="Category Rent"]');
    tap('[aria-label="Save expense"]');
    expect(api.calls[3].key).not.toBe(api.calls[2].key);
  });

  it('shows a sign-in message on 401 and a connection message otherwise', () => {
    api.failWith = 401;
    fill('5');
    tap('[aria-label="Save expense"]');
    expect(errorText()).toContain('Sign in to save');
    expect(root.querySelector('button.chip:not([aria-label])')?.textContent?.trim()).toBe('Sign in to save');
    api.failWith = 500;
    tap('[aria-label="Save expense"]');
    expect(errorText()).toContain('server had a problem');
    expect(errorText()).not.toContain('connection');
  });

  describe('signed out (T33)', () => {
    beforeEach(() => (owner = null));

    for (const online of [true, false]) {
      it(`${online ? 'online' : 'offline'}: refuses to save or queue, keeps the form, offers Sign in to save`, () => {
        spyOn(queue, 'isOnline').and.returnValue(online);
        tapKey('7');
        tap('[aria-label="Category Groceries"]');
        tap('[aria-label="Payment mode UPI"]');
        tap('[aria-label="Save expense"]');
        expect(api.calls.length).toBe(0);
        expect(queue.items().length).toBe(0);
        expect(errorText()).toContain('Sign in to save');
        expect(fixture.componentInstance.amountPaise()).toBe(700); // form kept
        expect(fixture.componentInstance.category()).toBe('GROCERIES');
        expect((root.querySelector('[aria-label="Save expense"]') as HTMLButtonElement).disabled).toBeFalse();
      });
    }

    it('Sign in to save keeps the entry as a draft for the round trip and remembers this screen', () => {
      const nav = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);
      tapKey('9');
      tap('[aria-label="Category Groceries"]');
      tap('[aria-label="Payment mode UPI"]');
      tap('[aria-label="Save expense"]');
      const btn = Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === 'Sign in to save')!;
      btn.click();
      expect(nav).toHaveBeenCalledWith('/sign-in');
      expect(TestBed.inject(AuthService).pendingReturnTo()).toBe('/spending/add');
      expect(JSON.parse(signin.data[DRAFT_KEY])).toEqual({ amountText: '9', category: 'GROCERIES', mode: 'UPI', tag: null, note: '' });

      // Back after sign-in: a new component restores the draft once.
      owner = 'user-1';
      const again = TestBed.createComponent(AddExpenseComponent).componentInstance;
      expect(again.amountPaise()).toBe(900);
      expect(again.category()).toBe('GROCERIES');
      expect(again.mode()).toBe('UPI');
      expect(signin.data[DRAFT_KEY]).toBeUndefined();
    });

    it('ignores a tampered draft', () => {
      signin.data[DRAFT_KEY] = JSON.stringify({ amountText: '<script>', category: 'NOPE', mode: 'BARTER', tag: 'X', note: 5 });
      const c = TestBed.createComponent(AddExpenseComponent).componentInstance;
      expect(c.amountText()).toBe('');
      expect(c.category()).toBeNull();
      expect(c.mode()).toBeNull();
      expect(c.tag()).toBeNull();
    });
  });

  describe('offline (T43)', () => {
    const save = () => tap('[aria-label="Save expense"]');

    it('navigator offline: queues without calling the API and shows the item as pending sync', () => {
      spyOn(queue, 'isOnline').and.returnValue(false);
      fill('5');
      save();
      expect(api.calls.length).toBe(0);
      expect(queue.pending().length).toBe(1);
      expect(queue.pending()[0].input.amountPaise).toBe(500);
      expect(root.querySelector('p.ok')?.textContent).toContain('Pending sync');
      const item = root.querySelector('.pending-item') as HTMLElement;
      expect(item.textContent).toContain('₹5');
      expect(item.textContent).toContain('Pending sync');
      expect(item.querySelector('.sr-only')?.textContent).toContain('not yet uploaded');
      expect(root.querySelector('#pend-h')?.textContent).toContain('Pending sync (1)');
    });

    it('storage failure: the expense is still queued in memory and the user is warned', () => {
      spyOn(queue, 'isOnline').and.returnValue(false);
      spyOn(queue, 'enqueue').and.returnValue(false);
      fill('5');
      save();
      expect(errorText()).toContain('could not store the expense');
      expect(root.querySelector('p.ok')?.textContent).toContain('Pending sync');
    });

    it('no response (status 0) while the browser claims online also queues, keeping the same key', () => {
      api.failWith = 0;
      fill('7');
      save();
      expect(api.calls.length).toBe(1);
      expect(queue.pending().length).toBe(1);
      expect(queue.pending()[0].key).toBe(api.calls[0].key);
      expect(errorText()).toBe('');
    });
  });
});
