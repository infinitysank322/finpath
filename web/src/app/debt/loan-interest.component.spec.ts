import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { LoanInterestApiService } from './loan-interest-api.service';
import { LoanInterestComponent } from './loan-interest.component';
import { LoanDetailView } from './loan-interest.models';
import { interestOption } from './loan-interest-chart-options';
import { exactInr, instalmentTotals } from './loan-interest-format';

const inst = (number: number, interestPaise: number, principalPaise: number) => ({
  number, openingPaise: 0, interestPaise, principalPaise, paymentPaise: interestPaise + principalPaise, closingPaise: 0,
});
const detail: LoanDetailView = {
  id: 'L1', lender: 'Bank Q', type: 'PERSONAL', emiUsedPaise: 123457, emiDerived: false, effectiveAnnualRate: '11.5',
  interestRemainingPaise: 1234583, totalRemainingPayablePaise: 11100050, instalmentsRemaining: 3, closed: false,
  pastDue: false, balloonFinalPayment: false,
  instalments: [inst(1, 700001, 3000017), inst(2, 400003, 3300013), inst(3, 134579, 3565437)],
};

class FakeApi {
  result: Observable<LoanDetailView> = of(detail);
  asked: string[] = [];
  detail(id: string) { this.asked.push(id); return this.result; }
}

describe('LoanInterestComponent', () => {
  let api: FakeApi;
  const setup = () => {
    TestBed.configureTestingModule({
      imports: [LoanInterestComponent],
      providers: [
        { provide: LoanInterestApiService, useValue: api },
        { provide: ActivatedRoute, useValue: { paramMap: of(convertToParamMap({ id: 'L1' })) } },
      ],
    });
    const fixture = TestBed.createComponent(LoanInterestComponent);
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement };
  };
  beforeEach(() => (api = new FakeApi()));

  it('requests the loan from the route id', () => {
    setup();
    expect(api.asked).toEqual(['L1']);
  });

  it('table totals equal the API figures to the paise', () => {
    const { fixture, root } = setup();
    const t = fixture.componentInstance.totals();
    expect(t.interestPaise).toBe(detail.interestRemainingPaise);
    expect(t.interestPaise).toBe(1234583);
    expect(t.principalPaise).toBe(9865467);
    expect(t.principalPaise + t.interestPaise).toBe(detail.totalRemainingPayablePaise);
    expect(t.paymentPaise).toBe(11100050);
    const cell = (k: string) => root.querySelector(`[data-total=${k}]`)!.textContent!.trim();
    expect(cell('interest')).toBe(cell('interest-remaining'));
    expect(cell('interest-remaining')).toBe('₹12,345.83');
    expect(cell('payable')).toBe('₹1,11,000.50');
  });

  it('gives the chart an accessible name and a legend-free text equivalent', () => {
    const { root } = setup();
    const wrap = root.querySelector('.fp-chart')!;
    expect(wrap.getAttribute('role')).toBe('img');
    expect(wrap.getAttribute('aria-label')).toContain('3 remaining instalments');
    expect(root.textContent).toContain('Educational');
    expect(root.textContent).toContain('Assumptions');
  });

  it('handles a closed loan without a chart', () => {
    api.result = of({ ...detail, closed: true, instalments: [], interestRemainingPaise: 0, totalRemainingPayablePaise: 0 });
    const { root } = setup();
    expect(root.textContent).toContain('closed');
    expect(root.querySelector('.fp-chart')).toBeNull();
  });

  it('handles empty instalments on an open loan', () => {
    api.result = of({ ...detail, instalments: [] });
    const { root } = setup();
    expect(root.textContent).toContain('no chart');
  });

  it('adds conditional assumptions and the effective rate label', () => {
    api.result = of({ ...detail, emiDerived: true, balloonFinalPayment: true, pastDue: true });
    const { root } = setup();
    const t = root.querySelector('.assumptions')!.textContent!;
    expect(t).toContain('Effective annual rate: 11.5%');
    expect(t).toContain('calculated');
    expect(t).toContain('last payment is larger');
    expect(t).toContain('Not every payment has been on time');
  });

  it('omits conditional assumptions when the flags are off', () => {
    const { root } = setup();
    const t = root.querySelector('.assumptions')!.textContent!;
    expect(t).not.toContain('calculated');
    expect(t).not.toContain('larger');
    expect(t).not.toContain('on time, so');
  });

  it('shows both figures when summed instalments differ from the summary', () => {
    api.result = of({ ...detail, interestRemainingPaise: 1234599, totalRemainingPayablePaise: 11100077 });
    const { root } = setup();
    const cell = (k: string) => root.querySelector(`[data-total=${k}]`)!.textContent!.trim();
    expect(cell('interest')).toBe('₹12,345.83');
    expect(cell('interest-remaining')).toBe('₹12,345.99');
    expect(cell('payable')).toBe('₹1,11,000.77');
    expect(root.textContent).toContain('Interest remaining (loan summary)');
  });

  it('retries after an error', () => {
    api.result = throwError(() => new HttpErrorResponse({ status: 0 }));
    const { fixture, root } = setup();
    api.result = of(detail);
    (root.querySelector('button.retry') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(api.asked).toEqual(['L1', 'L1']);
    expect(root.querySelector('[role=alert]')).toBeNull();
    expect(root.querySelector('.fp-chart')).not.toBeNull();
  });

  it('shows a friendly error', () => {
    api.result = throwError(() => new HttpErrorResponse({ status: 0 }));
    const { root } = setup();
    expect(root.querySelector('[role=alert]')!.textContent).toContain('offline');
  });
});

describe('interestOption', () => {
  const pal = { text: '#111', muted: '#222', border: '#333', principal: '#444', interest: '#555' };
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const o = interestOption(detail.instalments, pal, (p) => `P${p}`) as any;
  it('stacks principal under interest with the API paise untouched', () => {
    expect(o.series.map((s: { name: string }) => s.name)).toEqual(['Principal', 'Interest']);
    expect(o.series[0].stack).toBe(o.series[1].stack);
    expect(o.series[0].data).toEqual([3000017, 3300013, 3565437]);
    expect(o.series[1].data).toEqual([700001, 400003, 134579]);
  });
  it('does not rely on colour alone', () => {
    expect(o.legend.data).toEqual(['Principal', 'Interest']);
    expect(o.aria.decal.show).toBeTrue();
    expect(o.series[1].lineStyle.type).toBe('dashed');
  });
  it('sums integers', () => {
    expect(instalmentTotals(detail.instalments).paymentPaise).toBe(11100050);
  });
});

describe('exactInr', () => {
  it('groups lakhs and keeps paise', () => {
    expect(exactInr(1234583)).toBe('₹12,345.83');
    expect(exactInr(12345705)).toBe('₹1,23,457.05');
    expect(exactInr(5)).toBe('₹0.05');
    expect(exactInr(0)).toBe('₹0.00');
  });
});
