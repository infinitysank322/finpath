import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { IncomeComponent } from './income.component';
import { PlanApiService } from './plan-api.service';
import { CycleTotalView, IncomeInput, IncomeView } from './plan.models';

const salary: IncomeView = {
  id: 'i1', type: 'SALARY', frequency: 'MONTHLY', amountPaise: 10000000, startDate: '2026-01-01', endDate: null, countsInCurrentCycle: true,
};
const bonus: IncomeView = { ...salary, id: 'i2', type: 'BONUS', frequency: 'ONE_OFF', amountPaise: 1400000, countsInCurrentCycle: false };

class FakePlanApi {
  list: Observable<IncomeView[]> = of([salary, bonus]);
  created: IncomeInput[] = [];
  incomeList() { return this.list; }
  totalFail = false;
  incomeCycleTotal(): Observable<CycleTotalView> {
    if (this.totalFail) return throwError(() => new HttpErrorResponse({ status: 500 }));
    return of({ cycleStart: '2026-10-25', cycleEndExclusive: '2026-11-25', totalPaise: 12700000, items: [] });
  }
  incomeCreate(b: IncomeInput) { this.created.push(b); return of(salary); }
  incomeReplace() { return of(salary); }
  incomeDelete(): Observable<void> { return of(undefined); }
}

describe('IncomeComponent', () => {
  let api: FakePlanApi;
  const setup = () => {
    TestBed.configureTestingModule({ imports: [IncomeComponent], providers: [{ provide: PlanApiService, useValue: api }] });
    const fixture = TestBed.createComponent(IncomeComponent);
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement };
  };
  beforeEach(() => (api = new FakePlanApi()));

  it('shows the API cycle total as the headline, in lakh grouping', () => {
    const { root } = setup();
    expect(root.querySelector('.headline .value')?.textContent).toBe('₹1,27,000');
  });

  it('lists sources with a recurring or one-off badge and the API counts-this-cycle flag', () => {
    const { root } = setup();
    const rows = root.querySelectorAll('li.row');
    expect(rows.length).toBe(2);
    expect(rows[0].textContent).toContain('Recurring');
    expect(rows[0].textContent).toContain('Counts this cycle');
    expect(rows[1].textContent).toContain('One-off');
    expect(rows[1].textContent).not.toContain('Counts this cycle');
    expect(rows[1].textContent).toContain('₹14,000');
  });

  it('sends integer paise and no end date for a one-off', () => {
    const { fixture, root } = setup();
    (root.querySelector('#inc-add') as HTMLElement).click();
    fixture.detectChanges();
    const input = root.querySelector('#inc-amount') as HTMLInputElement;
    input.value = '14000';
    input.dispatchEvent(new Event('input'));
    const freq = root.querySelector('#inc-freq') as HTMLSelectElement;
    freq.value = 'ONE_OFF';
    freq.dispatchEvent(new Event('change'));
    fixture.detectChanges();
    (root.querySelector('button[type=submit]') as HTMLElement).click();
    expect(api.created[0].amountPaise).toBe(1400000);
    expect(api.created[0].endDate).toBeNull();
    expect(api.created[0].frequency).toBe('ONE_OFF');
  });

  it('labels every button and shows Please sign in on 401', () => {
    api.list = throwError(() => new HttpErrorResponse({ status: 401 }));
    const { root } = setup();
    root.querySelectorAll('button').forEach((b) => {
      const label = b.getAttribute('aria-label');
      if (label) expect(label).toContain((b.textContent ?? '').trim());
    });
    expect(root.querySelector('.error')?.textContent).toContain('Please sign in');
  });

  const click = (root: HTMLElement, sel: string, fixture: { detectChanges(): void }) => {
    (root.querySelector(sel) as HTMLElement).click();
    fixture.detectChanges();
  };

  it('needs a confirm step before removing', () => {
    const del = spyOn(api, 'incomeDelete').and.callThrough();
    const { fixture, root } = setup();
    click(root, 'li.row .fp-actions button:nth-child(2)', fixture);
    expect(del).not.toHaveBeenCalled();
    click(root, 'li.row .fp-actions button:nth-child(3)', fixture); // Keep
    click(root, 'li.row .fp-actions button:nth-child(2)', fixture);
    click(root, 'li.row .fp-actions button.fp-btn', fixture);
    expect(del).toHaveBeenCalledTimes(1);
  });

  it('disables Remove while the request is pending', () => {
    spyOn(api, 'incomeDelete').and.returnValue(new Subject<void>());
    const { fixture, root } = setup();
    click(root, 'li.row .fp-actions button:nth-child(2)', fixture);
    click(root, 'li.row .fp-actions button.fp-btn', fixture);
    root.querySelectorAll('li.row button').forEach((b) => expect((b as HTMLButtonElement).disabled).toBeTrue());
  });

  it('clears the headline when the reload fails', () => {
    api.totalFail = true;
    const { root } = setup();
    expect(root.querySelector('.headline')).toBeNull();
    expect(root.querySelector('.error')?.textContent).toContain('server had a problem');
  });

  it('flags an end date before the start date inline and blocks save', () => {
    const { fixture, root } = setup();
    click(root, '#inc-add', fixture);
    const set = (id: string, v: string) => {
      const el = root.querySelector(id) as HTMLInputElement;
      el.value = v;
      el.dispatchEvent(new Event('input'));
    };
    set('#inc-amount', '100');
    set('#inc-start', '2026-10-25');
    set('#inc-end', '2026-10-01');
    fixture.detectChanges();
    expect(root.querySelector('#inc-date-err')?.textContent).toContain('end date');
    expect((root.querySelector('button[type=submit]') as HTMLButtonElement).disabled).toBeTrue();
  });

  it('renders no empty alert or status elements', () => {
    const { root } = setup();
    root.querySelectorAll('[role=alert], [role=status]').forEach((e) => expect((e.textContent ?? '').trim()).not.toBe(''));
  });
});
