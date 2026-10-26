import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, Subject, of, throwError } from 'rxjs';
import { PlanApiService } from './plan-api.service';
import { RecurringComponent } from './recurring.component';
import { MaterialiseResult, TemplateInput, TemplateView } from './plan.models';

const quarterly: TemplateView = {
  id: 't1', categoryCode: 'INSURANCE', amountPaise: 600000, frequency: 'QUARTERLY', mode: 'UPI', tag: null,
  startDate: '2026-10-25', endDate: null,
  perCycleSpread: [{ index: 0, amountPaise: 200000 }, { index: 1, amountPaise: 200000 }, { index: 2, amountPaise: 200000 }],
  currentInstalmentPaise: 200000,
};
const odd: TemplateView = {
  ...quarterly, id: 't2', amountPaise: 1000, categoryCode: 'UTILITIES', currentInstalmentPaise: null,
  perCycleSpread: [{ index: 0, amountPaise: 333 }, { index: 1, amountPaise: 333 }, { index: 2, amountPaise: 334 }],
};

class FakePlanApi {
  list: Observable<TemplateView[]> = of([quarterly, odd]);
  created: TemplateInput[] = [];
  templateList() { return this.list; }
  templateCreate(b: TemplateInput) { this.created.push(b); return of(quarterly); }
  templateReplace() { return of(quarterly); }
  templateDelete(): Observable<void> { return of(undefined); }
  materialise(): Observable<MaterialiseResult> {
    return of({ cycleStart: '2026-10-25', cycleEndExclusive: '2026-11-25', items: [], createdCount: 1, postedPaise: 200000 });
  }
}

describe('RecurringComponent', () => {
  let api: FakePlanApi;
  const setup = () => {
    TestBed.configureTestingModule({ imports: [RecurringComponent], providers: [{ provide: PlanApiService, useValue: api }] });
    const fixture = TestBed.createComponent(RecurringComponent);
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement };
  };
  beforeEach(() => (api = new FakePlanApi()));

  it('shows a quarterly template with both the bill amount and the API per-cycle spread', () => {
    const { root } = setup();
    const row = root.querySelectorAll('li.row')[0] as HTMLElement;
    const text = row.textContent ?? '';
    expect(text).toContain('Bill ₹6,000');
    expect(text).toContain('every 3 months');
    expect(text).toContain('Instalment 1 of 3: ₹2,000');
    expect(text).toContain('Instalment 3 of 3: ₹2,000');
    expect(text).toContain('Due this cycle: ₹2,000');
  });

  it('shows the API remainder paise exactly, not a client division', () => {
    const { root } = setup();
    const text = (root.querySelectorAll('li.row')[1] as HTMLElement).textContent ?? '';
    expect(text).toContain('Bill ₹10');
    expect(text).toContain('Instalment 1 of 3: ₹3.33');
    expect(text).toContain('Instalment 3 of 3: ₹3.34');
    expect(text).toContain('Nothing due this cycle');
  });

  it('labels every control and uses no calendar-month names', () => {
    const { root } = setup();
    root.querySelectorAll('button').forEach((b) => {
      const label = b.getAttribute('aria-label');
      if (label) expect(label).withContext(label).toContain((b.textContent ?? '').trim()); // label contains visible text
    });
    expect(root.textContent).not.toMatch(/January|February|March|April|June|July|August|September|October|November|December/);
  });

  it('sends integer paise from typed rupees when saving', () => {
    const { fixture, root } = setup();
    (root.querySelector('#rec-add') as HTMLElement).click();
    fixture.detectChanges();
    const input = root.querySelector('#rec-amount') as HTMLInputElement;
    input.value = '6000.50';
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    (root.querySelector('button[type=submit]') as HTMLElement).click();
    expect(api.created.length).toBe(1);
    expect(api.created[0].amountPaise).toBe(600050);
    expect(api.created[0].frequency).toBe('QUARTERLY');
  });

  it('shows Please sign in on 401', () => {
    api.list = throwError(() => new HttpErrorResponse({ status: 401 }));
    const { root } = setup();
    expect(root.querySelector('.error')?.textContent).toContain('Please sign in');
  });

  it('reports the materialise result from the API', () => {
    const { fixture, root } = setup();
    (root.querySelector('#rec-post') as HTMLElement).click();
    fixture.detectChanges();
    expect(root.textContent).toContain('Posted 1 bill, ₹2,000 in total.');
  });

  const click = (root: HTMLElement, sel: string, fixture: { detectChanges(): void }) => {
    (root.querySelector(sel) as HTMLElement).click();
    fixture.detectChanges();
  };

  it('needs a confirm step before removing, and Keep cancels', () => {
    const del = spyOn(api, 'templateDelete').and.callThrough();
    const { fixture, root } = setup();
    click(root, 'li.row .fp-actions button:nth-child(2)', fixture); // Remove
    expect(del).not.toHaveBeenCalled();
    expect(root.textContent).toContain('can be posted a second time');
    click(root, 'li.row .fp-actions button:nth-child(3)', fixture); // Keep
    expect(del).not.toHaveBeenCalled();
    click(root, 'li.row .fp-actions button:nth-child(2)', fixture);
    click(root, 'li.row .fp-actions button.fp-btn', fixture); // Confirm remove
    expect(del).toHaveBeenCalledTimes(1);
  });

  it('disables Remove while the request is pending', () => {
    const pending = new Subject<void>();
    spyOn(api, 'templateDelete').and.returnValue(pending);
    const { fixture, root } = setup();
    click(root, 'li.row .fp-actions button:nth-child(2)', fixture);
    click(root, 'li.row .fp-actions button.fp-btn', fixture);
    root.querySelectorAll('li.row button').forEach((b) => expect((b as HTMLButtonElement).disabled).toBeTrue());
    pending.next();
    pending.complete();
  });

  it('clears the posted result when the list changes', () => {
    const { fixture, root } = setup();
    click(root, '#rec-post', fixture);
    expect(root.textContent).toContain('Posted 1 bill');
    click(root, '#rec-add', fixture);
    expect(root.textContent).not.toContain('Posted 1 bill');
  });

  it('flags an end date before the start date inline and blocks save', () => {
    const { fixture, root } = setup();
    click(root, '#rec-add', fixture);
    const set = (id: string, v: string) => {
      const el = root.querySelector(id) as HTMLInputElement;
      el.value = v;
      el.dispatchEvent(new Event('input'));
    };
    set('#rec-amount', '100');
    set('#rec-start', '2026-10-25');
    set('#rec-end', '2026-10-01');
    fixture.detectChanges();
    expect(root.querySelector('#rec-date-err')?.textContent).toContain('end date');
    expect((root.querySelector('button[type=submit]') as HTMLButtonElement).disabled).toBeTrue();
  });

  it('renders no empty alert or status elements', () => {
    const { root } = setup();
    root.querySelectorAll('[role=alert], [role=status]').forEach((e) => expect((e.textContent ?? '').trim()).not.toBe(''));
  });
});
