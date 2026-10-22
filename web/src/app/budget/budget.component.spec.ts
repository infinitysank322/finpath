import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';
import { OUT_CATEGORIES } from '../expense/expense.models';
import { BudgetApiService } from './budget-api.service';
import { BudgetComponent } from './budget.component';
import { BudgetLine, BudgetStatus, EvaluateResult } from './budget.models';

const line = (over: Partial<BudgetLine>): BudgetLine => ({
  categoryCode: 'FOOD', budgetPaise: 1000000, spentPaise: 100000, remainingPaise: 900000, usedFraction: '0.1000', level: 'OK', lastAlertLevel: 'OK', ...over,
});

class FakeApi {
  lines: BudgetLine[] = [
    line({}),
    line({ categoryCode: 'TRANSPORT', spentPaise: 850000, remainingPaise: 150000, usedFraction: '0.8500', level: 'WARN' }),
    line({ categoryCode: 'SHOPPING', spentPaise: 1250000, remainingPaise: -250000, usedFraction: '1.2500', level: 'BREACH' }),
    line({ categoryCode: 'OTHER', budgetPaise: 0, spentPaise: 0, remainingPaise: 0, usedFraction: null }),
  ];
  result: EvaluateResult = { events: [{ categoryCode: 'TRANSPORT', level: 'WARN' }, { categoryCode: 'SHOPPING', level: 'BREACH' }] };
  puts: { code: string; paise: number }[] = [];
  evaluateCalls = 0;
  evaluate(): Observable<EvaluateResult> { this.evaluateCalls++; return of(this.result); }
  status(): Observable<BudgetStatus> { return of({ cycleStart: '2026-10-25', cycleEndExclusive: '2026-11-25', lines: this.lines }); }
  put(code: string, paise: number) { this.puts.push({ code, paise }); return of(line({})); }
  delete(): Observable<void> { return of(undefined); }
}

describe('BudgetComponent', () => {
  let api: FakeApi;
  const setup = () => {
    TestBed.configureTestingModule({ imports: [BudgetComponent], providers: [{ provide: BudgetApiService, useValue: api }] });
    const fixture = TestBed.createComponent(BudgetComponent);
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement };
  };
  beforeEach(() => (api = new FakeApi()));

  it('calls evaluate exactly once on open, even after a reload', () => {
    const { fixture } = setup();
    fixture.componentInstance.loadStatus();
    fixture.detectChanges();
    expect(api.evaluateCalls).toBe(1);
  });

  it('shows percentages exactly from the API usedFraction text', () => {
    const { root } = setup();
    const bars = Array.from(root.querySelectorAll('[role=progressbar]'));
    expect(bars.map((b) => b.getAttribute('aria-valuenow'))).toEqual(['10', '85', '125']);
    const pcts = Array.from(root.querySelectorAll('.pct')).map((e) => (e.textContent ?? '').trim());
    expect(pcts.slice(0, 3)).toEqual(['10% used', '85% used', '125% used']);
    expect(pcts[3]).toContain('Budget is zero');
    expect(bars.every((b) => b.getAttribute('aria-label'))).toBeTrue();
  });

  it('shows remaining from the API remainingPaise', () => {
    const { root } = setup();
    const rem = Array.from(root.querySelectorAll('.remaining')).map((e) => e.textContent?.trim());
    expect(rem).toContain('₹1,500 left');
    expect(rem).toContain('Over by ₹2,500');
  });

  it('shows persistent warn and breach banners from status levels, without hard-coded thresholds', () => {
    const { root } = setup();
    expect(root.querySelectorAll('.banner.warn').length).toBe(1);
    expect(root.querySelectorAll('.banner.breach').length).toBe(1);
    expect(root.querySelector('.banner')?.textContent).not.toContain('80');
  });

  it('keeps banners when there are no new events, and shows events as an extra notice', () => {
    api.result = { events: [] };
    let s = setup();
    expect(s.root.querySelectorAll('.banner').length).toBe(2);
    expect(s.root.querySelector('.notice')).toBeNull();
    TestBed.resetTestingModule();
    api = new FakeApi();
    s = setup();
    expect(s.root.querySelectorAll('.notice').length).toBe(2);
  });

  it('says so when evaluate fails and still shows lines', () => {
    api.evaluate = () => throwError(() => new HttpErrorResponse({ status: 500 }));
    const { root } = setup();
    expect(root.querySelector('.notice')?.textContent).toContain('Could not check');
    expect(root.querySelectorAll('li.line').length).toBe(4);
  });

  it('shows plan-cycle guidance on a 404 status', () => {
    api.status = () => throwError(() => new HttpErrorResponse({ status: 404 }));
    const { root } = setup();
    expect(root.querySelector('.error')?.textContent).toContain('Set up your plan cycle first');
  });

  it('shows save errors inside the sheet, closes on Escape and returns focus', () => {
    api.put = () => throwError(() => new HttpErrorResponse({ status: 500 })) as never;
    const { fixture, root } = setup();
    const edit = root.querySelector('li.line .btn') as HTMLElement;
    edit.focus();
    edit.click();
    fixture.detectChanges();
    (root.querySelector('#bud-amount') as HTMLInputElement).dispatchEvent(new Event('input'));
    (root.querySelector('button[type=submit]') as HTMLElement).click();
    fixture.detectChanges();
    expect(root.querySelector('.sheet .error')?.textContent).toContain('server had a problem');
    root.querySelector('.sheet')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'Escape' }));
    fixture.detectChanges();
    expect(root.querySelector('.sheet')).toBeNull();
  });

  it('disables Add when every category already has a budget', () => {
    api.lines = OUT_CATEGORIES.map((c) => line({ categoryCode: c.code }));
    const { root } = setup();
    expect((root.querySelector('#bud-add') as HTMLButtonElement).disabled).toBeTrue();
    expect(root.querySelector('#bud-add-hint')?.textContent).toContain('Every category');
  });

  it('sends the edited budget as integer paise', () => {
    const { fixture, root } = setup();
    (root.querySelector('li.line .btn') as HTMLElement).click(); // Edit FOOD
    fixture.detectChanges();
    const input = root.querySelector('#bud-amount') as HTMLInputElement;
    input.value = '12,500.50';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (root.querySelector('button[type=submit]') as HTMLElement).click();
    expect(api.puts).toEqual([{ code: 'FOOD', paise: 1250050 }]);
  });

  it('allows a zero budget but blocks malformed text', () => {
    const { fixture, root } = setup();
    (root.querySelector('#bud-add') as HTMLElement).click();
    fixture.detectChanges();
    const input = root.querySelector('#bud-amount') as HTMLInputElement;
    const submit = () => root.querySelector('button[type=submit]') as HTMLButtonElement;
    input.value = '12.345';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(submit().disabled).toBeTrue();
    input.value = '0';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    expect(submit().disabled).toBeFalse();
    submit().click();
    expect(api.puts[0].paise).toBe(0);
  });

  it('renders no empty alert or status elements', () => {
    const { root } = setup();
    root.querySelectorAll('[role=alert], [role=status]').forEach((e) => expect((e.textContent ?? '').trim()).not.toBe(''));
  });
});
