import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { PrepayApiService } from './prepay-api.service';
import { buildQuery, cycleEndLabel, parseWhole, rupeesToPaise } from './prepay-format';
import { PrepayComponent } from './prepay.component';
import { PrepayQuery, PrepaymentView } from './prepay.models';

const view: PrepaymentView = {
  loanId: 'L1',
  assumptions: { mode: 'lump_sum', annualRate: '8.35', principalOutstandingPaise: 123456789, currentEmiPaise: 4003201, tenureMonths: 297, firstInstalmentFrom: '2026-10-23', note: 'No fees modelled.' },
  newEmiPaise: 4003201, baselineInstalments: 297, newInstalments: 156, instalmentsSaved: 141,
  baselineInterestPaise: 9876543210, newInterestPaise: 5000000001, interestSavedPaise: 4876543209,
  baselinePayoffCycle: { start: '2051-06-25', endExclusive: '2051-07-25' }, newPayoffCycle: { start: '2039-09-25', endExclusive: '2039-10-25' },
};

class FakeApi {
  result: Observable<PrepaymentView> = of(view);
  asked: { id: string; q: PrepayQuery }[] = [];
  simulate(id: string, q: PrepayQuery) { this.asked.push({ id, q }); return this.result; }
}

describe('PrepayComponent', () => {
  let api: FakeApi;
  const setup = () => {
    TestBed.configureTestingModule({
      imports: [PrepayComponent],
      providers: [
        { provide: PrepayApiService, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: new Map([['id', 'L1']]) } } },
      ],
    });
    const fixture = TestBed.createComponent(PrepayComponent);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, root: fixture.nativeElement as HTMLElement };
  };
  beforeEach(() => (api = new FakeApi()));

  it('sends paise to the API and renders its figures exactly', () => {
    const { fixture, c, root } = setup();
    c.lump.set('1,00,000.50');
    c.run();
    fixture.detectChanges();
    expect(api.asked).toEqual([{ id: 'L1', q: { mode: 'lump_sum', lumpPaise: 10000050 } }]);
    const cell = (k: string) => root.querySelector(`[data-r=${k}]`)!.textContent!.trim();
    expect(cell('base-int')).toBe('₹9,87,65,432.10');
    expect(cell('new-int')).toBe('₹5,00,00,000.01');
    expect(root.querySelector('[data-r=saved]')!.textContent).toContain('₹4,87,65,432.09');
    expect(cell('new-n')).toBe('156');
  });

  it('states the assumed rate and the floating-rate caveat, with no named product', () => {
    const { fixture, c, root } = setup();
    c.lump.set('1000');
    c.run();
    fixture.detectChanges();
    expect(root.querySelector('[data-r=rate]')!.textContent).toContain('8.35%');
    expect(root.textContent).toContain('floating rate');
    expect(root.textContent).toContain('not advice');
    expect(root.textContent).not.toMatch(/mutual fund|SIP|stock|ETF|FD\b/i);
  });

  it('clears the old result when the mode changes', () => {
    const { fixture, c, root } = setup();
    c.lump.set('1000');
    c.run();
    fixture.detectChanges();
    c.setMode('close_in');
    fixture.detectChanges();
    expect(root.querySelector('[data-r=saved]')).toBeNull();
  });

  it('does not call the API on bad input', () => {
    const { c, root, fixture } = setup();
    c.run();
    fixture.detectChanges();
    expect(api.asked.length).toBe(0);
    expect(root.querySelector('[role=alert]')).not.toBeNull();
  });

  it('explains a 400 and an offline failure', () => {
    const { c, root, fixture } = setup();
    c.setMode('close_in');
    c.months.set('120');
    api.result = throwError(() => new HttpErrorResponse({ status: 400 }));
    c.run();
    fixture.detectChanges();
    expect(root.querySelector('[role=alert]')!.textContent).toContain('cannot be run');
    api.result = throwError(() => new HttpErrorResponse({ status: 0 }));
    c.run();
    fixture.detectChanges();
    expect(root.querySelector('[role=alert]')!.textContent).toContain('offline');
  });
});

describe('prepay-format', () => {
  it('parses rupees to paise without floats', () => {
    expect(rupeesToPaise('1,50,000')).toBe(15000000);
    expect(rupeesToPaise('₹10.5')).toBe(1050);
    expect(rupeesToPaise('0.07')).toBe(7);
    expect(rupeesToPaise('1.234')).toBeNull();
    expect(rupeesToPaise('-5')).toBeNull();
    expect(rupeesToPaise('')).toBeNull();
  });
  it('parses whole numbers', () => {
    expect(parseWhole(' 12 ')).toBe(12);
    expect(parseWhole('1.5')).toBeNull();
  });
  it('builds each mode', () => {
    const base = { lump: '', after: '', newEmi: '', months: '' };
    expect(buildQuery({ ...base, mode: 'lump_sum', lump: '500', after: '6' })).toEqual({ query: { mode: 'lump_sum', lumpPaise: 50000, afterInstalment: 6 } });
    expect(buildQuery({ ...base, mode: 'lump_sum', lump: '500', after: 'x' })).toEqual({ error: jasmine.any(String) });
    expect(buildQuery({ ...base, mode: 'lump_sum', lump: '0' })).toEqual({ error: jasmine.any(String) });
    expect(buildQuery({ ...base, mode: 'increase_emi', newEmi: '45000' })).toEqual({ query: { mode: 'increase_emi', newEmiPaise: 4500000 } });
    expect(buildQuery({ ...base, mode: 'increase_emi' })).toEqual({ error: jasmine.any(String) });
    expect(buildQuery({ ...base, mode: 'close_in', months: '156' })).toEqual({ query: { mode: 'close_in', closeMonths: 156 } });
    expect(buildQuery({ ...base, mode: 'close_in', months: '0' })).toEqual({ error: jasmine.any(String) });
  });
  it('labels payoff cycles', () => {
    expect(cycleEndLabel(null)).toBe('Not available');
    expect(cycleEndLabel({ start: '2039-09-25', endExclusive: '2039-10-25' })).toContain('2039');
  });
});
