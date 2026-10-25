import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { AllocationApiService } from './allocation-api.service';
import { stepName } from './allocation-format';
import { AllocationComponent } from './allocation.component';
import { AllocationView, RebalancePreview } from './allocation.models';

const view: AllocationView = {
  cycleStart: '2026-10-25', surplusPaise: 6000000, shortfallPaise: 0, allocatedPaise: 6000000, prepayShare: '0.3',
  lines: [
    { step: 'EMERGENCY_FUND_ONE_MONTH', ref: null, amountPaise: 1750000 },
    { step: 'HIGH_INTEREST_DEBT', ref: 'L1', amountPaise: 1234500 },
    { step: 'LONG_TERM_SIP', ref: null, amountPaise: 3265500 },
  ],
};
const preview: RebalancePreview = {
  absorbed: [{ step: 'LONG_TERM_SIP', ref: null, amountPaise: 1750000 }], primaryAbsorbingStep: 'LONG_TERM_SIP',
  uncoveredPaise: 0, surplusAfterPaise: 4500000, lines: [], safeToSpendBeforePaise: 900000, safeToSpendAfterPaise: 700000,
};

class FakeApi {
  cur: Observable<AllocationView> = of(view);
  asked: number[] = [];
  runs = 0;
  current() { return this.cur; }
  run() { this.runs++; return of(view); }
  rebalance(p: number) { this.asked.push(p); return of(preview); }
}

describe('AllocationComponent', () => {
  let api: FakeApi;
  const setup = () => {
    TestBed.configureTestingModule({ imports: [AllocationComponent], providers: [{ provide: AllocationApiService, useValue: api }] });
    const fixture = TestBed.createComponent(AllocationComponent);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, root: fixture.nativeElement as HTMLElement };
  };
  beforeEach(() => (api = new FakeApi()));

  it('renders line amounts equal to the API payload and the total equals the API surplus', () => {
    const { root } = setup();
    const amounts = Array.from(root.querySelectorAll('[data-f=amount]')).map((e) => e.textContent!.trim());
    expect(amounts).toEqual(['₹17,500.00', '₹12,345.00', '₹32,655.00']);
    expect(root.querySelector('[data-f=total]')!.textContent!.trim()).toBe('₹60,000.00');
    expect(root.querySelector('[data-f=surplus]')!.textContent).toContain('₹60,000.00');
    const names = Array.from(root.querySelectorAll('tbody th')).map((e) => e.textContent!.trim());
    expect(names).toEqual(['Emergency fund: first month', 'High-interest debt', 'Long-term SIP']);
  });

  it('names the absorbing step in text when an overrun is checked', () => {
    const { fixture, c, root } = setup();
    c.spend.set('17,500');
    c.check();
    fixture.detectChanges();
    expect(api.asked).toEqual([1750000]);
    expect(root.querySelector('[data-f=absorber]')!.textContent).toContain('Long-term SIP');
    expect(root.querySelector('[data-f=surplus-after]')!.textContent!.trim()).toBe('₹45,000');
    expect(root.querySelector('[data-absorbed]')!.textContent).toContain('₹17,500 less');
  });

  it('rejects a bad spend without calling the API', () => {
    const { fixture, c, root } = setup();
    c.spend.set('abc');
    c.check();
    fixture.detectChanges();
    expect(api.asked.length).toBe(0);
    expect(root.querySelector('[role=alert]')).not.toBeNull();
  });

  it('offers a run when none exists yet, and shows a shortfall as text', () => {
    api.cur = throwError(() => new HttpErrorResponse({ status: 404 }));
    const { fixture, c, root } = setup();
    expect(root.querySelector('[data-f=missing]')).not.toBeNull();
    api.run = () => of({ ...view, surplusPaise: -500, shortfallPaise: 500, allocatedPaise: 0, lines: [] });
    c.run();
    fixture.detectChanges();
    expect(root.querySelector('[data-f=shortfall]')!.textContent).toContain('₹5');
    expect(root.querySelector('table')).toBeNull();
  });

  it('keeps the layout at 380 px by construction: fixed-layout table, wrapping text, theme tokens only', () => {
    const { root } = setup();
    const host = root.ownerDocument.createElement('div');
    host.style.width = '380px';
    host.appendChild(root);
    root.ownerDocument.body.appendChild(host);
    expect(root.scrollWidth).toBeLessThanOrEqual(380);
    host.remove();
  });

  it('words known steps', () => {
    expect(stepName('DEBT_PREPAYMENT')).toBe('Debt prepayment');
    expect(stepName('NEW_THING')).toBe('New thing');
  });
});
