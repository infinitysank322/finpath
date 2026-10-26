import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { HoldingApiService } from './holding-api.service';
import { HoldingAggregateView, HoldingInput, HoldingView } from './holding.models';
import { InvestmentsComponent } from './investments.component';

const epf: HoldingView = { id: 'h1', assetClass: 'epf', name: 'Employer EPF', investedPaise: 0, currentValuePaise: 1250000, monthlyContributionPaise: 300000 };
const gold: HoldingView = { id: 'h2', assetClass: 'gold', name: 'Coins', investedPaise: 500000, currentValuePaise: 750000, monthlyContributionPaise: 0 };

const c = (assetClass: string, v: number, count = v > 0 ? 1 : 0) => ({ assetClass, investedPaise: 0, currentValuePaise: v, monthlyContributionPaise: 0, count });
const agg: HoldingAggregateView = {
  classes: [c('epf', 1250000), c('ppf', 0), c('nps', 0), c('mf', 0), c('stocks', 0), c('fd', 0), c('gold', 750000)],
  total: c('total', 2000000, 2),
} as HoldingAggregateView;

class FakeApi {
  list$: Observable<HoldingView[]> = of([epf, gold]);
  aggregate$: Observable<HoldingAggregateView> = of(agg);
  created: HoldingInput[] = [];
  replaced: { id: string; body: HoldingInput }[] = [];
  removed: string[] = [];
  list() { return this.list$; }
  aggregate() { return this.aggregate$; }
  create(b: HoldingInput) { this.created.push(b); return of(epf); }
  replace(id: string, body: HoldingInput) { this.replaced.push({ id, body }); return of(epf); }
  remove(id: string): Observable<void> { this.removed.push(id); return of(undefined); }
}

describe('InvestmentsComponent', () => {
  let api: FakeApi;
  const setup = () => {
    TestBed.configureTestingModule({ imports: [InvestmentsComponent], providers: [{ provide: HoldingApiService, useValue: api }] });
    const fixture = TestBed.createComponent(InvestmentsComponent);
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement };
  };
  const btn = (root: HTMLElement, text: string) => Array.from(root.querySelectorAll('button')).find((b) => b.textContent?.trim() === text)!;
  const type = (fixture: { detectChanges(): void }, root: HTMLElement, id: string, v: string) => {
    const i = root.querySelector<HTMLInputElement>('#' + id)!;
    i.value = v;
    i.dispatchEvent(new Event('input'));
    fixture.detectChanges();
  };
  beforeEach(() => (api = new FakeApi()));

  it('lists holdings with the educational label', () => {
    const { root } = setup();
    expect(root.textContent).toContain('Educational information, not personal advice');
    expect(root.querySelectorAll('li.holding').length).toBe(2);
    expect(root.textContent).toContain('Employer EPF');
  });

  it('has a donut with an aria description and a table equivalent with class, amount and share', () => {
    const { root } = setup();
    const img = root.querySelector('[role=img]')!;
    expect(img.getAttribute('aria-label')).toContain('EPF (provident fund) ₹12,500, 63 percent');
    expect(img.getAttribute('aria-label')).toContain('Gold ₹7,500, 38 percent');
    const rows = Array.from(root.querySelectorAll('tbody tr')).map((r) => r.textContent!.replace(/\s+/g, ' '));
    expect(rows.length).toBe(2); // classes not held are not listed
    expect(rows[0]).toContain('EPF (provident fund)');
    expect(rows[0]).toContain('₹12,500');
    expect(rows[0]).toContain('63%');
    expect(root.querySelector('tfoot')!.textContent).toContain('₹20,000');
  });

  it('shows no chart when nothing has a value', () => {
    api.list$ = of([]);
    api.aggregate$ = of({ classes: [c('epf', 0)], total: c('total', 0, 0) } as HoldingAggregateView);
    const { root } = setup();
    expect(root.querySelector('[role=img]')).toBeNull();
    expect(root.textContent).toContain('No holdings yet');
  });

  it('warns not to enter account numbers and enforces the 60 character label', () => {
    const { fixture, root } = setup();
    btn(root, 'Add holding').click();
    fixture.detectChanges();
    expect(root.querySelector('#hold-hint')!.textContent).toContain('Do NOT enter account, folio, demat, PAN or UAN numbers');
    expect(root.querySelector('#hold-name')!.getAttribute('maxlength')).toBe('60');
    expect(btn(root, 'Save').disabled).toBeTrue();
    type(fixture, root, 'hold-name', 'x'.repeat(61));
    expect(btn(root, 'Save').disabled).toBeTrue();
  });

  it('adds a holding with rupees converted to integer paise', () => {
    const { fixture, root } = setup();
    btn(root, 'Add holding').click();
    fixture.detectChanges();
    type(fixture, root, 'hold-name', ' Index fund ');
    type(fixture, root, 'hold-invested', '1,00,000');
    type(fixture, root, 'hold-current', '1,25,000.5');
    type(fixture, root, 'hold-monthly', '5000');
    btn(root, 'Save').click();
    expect(api.created).toEqual([{ assetClass: 'mf', name: 'Index fund', investedPaise: 10000000, currentValuePaise: 12500050, monthlyContributionPaise: 500000 }]);
    fixture.detectChanges();
    expect(root.textContent).toContain('Holding saved.');
  });

  it('blank amounts mean 0; malformed amounts block Save', () => {
    const { fixture, root } = setup();
    btn(root, 'Add holding').click();
    fixture.detectChanges();
    type(fixture, root, 'hold-name', 'Cash FD');
    type(fixture, root, 'hold-current', 'abc');
    expect(btn(root, 'Save').disabled).toBeTrue();
    type(fixture, root, 'hold-current', '');
    btn(root, 'Save').click();
    expect(api.created[0].currentValuePaise).toBe(0);
  });

  it('shows the EPF note on the form: entered by the user, never derived', () => {
    const { fixture, root } = setup();
    root.querySelector<HTMLButtonElement>('[aria-label="Edit Employer EPF"]')!.click();
    fixture.detectChanges();
    expect(root.textContent).toContain('never worked out for you');
    expect((root.querySelector('#hold-current') as HTMLInputElement).value).toBe('12500');
  });

  it('offers EPF only when no other EPF holding exists', () => {
    const { fixture, root } = setup();
    btn(root, 'Add holding').click();
    fixture.detectChanges();
    const opts = () => Array.from(root.querySelectorAll('#hold-class option')).map((o) => (o as HTMLOptionElement).value);
    expect(opts()).not.toContain('epf');
    expect(opts()).toContain('mf');
  });

  it('edits through PUT with the id', () => {
    const { fixture, root } = setup();
    root.querySelector<HTMLButtonElement>('[aria-label="Edit Coins"]')!.click();
    fixture.detectChanges();
    type(fixture, root, 'hold-current', '8000');
    btn(root, 'Save').click();
    expect(api.replaced[0].id).toBe('h2');
    expect(api.replaced[0].body.currentValuePaise).toBe(800000);
  });

  it('removes only after a confirm tap', () => {
    const { fixture, root } = setup();
    root.querySelector<HTMLButtonElement>('[aria-label="Remove Coins"]')!.click();
    fixture.detectChanges();
    expect(api.removed).toEqual([]);
    root.querySelector<HTMLButtonElement>('[aria-label="Confirm remove Coins"]')!.click();
    expect(api.removed).toEqual(['h2']);
  });

  it('shows a fixed message when loading fails', () => {
    api.aggregate$ = throwError(() => new HttpErrorResponse({ status: 500 }));
    const { root } = setup();
    expect(root.querySelector('[role=alert]')!.textContent).toContain('server had a problem');
  });
});
