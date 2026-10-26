import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { SinkingFundApiService } from './sinking-fund-api.service';
import { statusText } from './sinking-fund-format';
import { SinkingFundComponent } from './sinking-fund.component';
import { SinkingFundView } from './sinking-fund.models';

const behind: SinkingFundView = {
  loanId: 'L1', cyclesRemaining: 10, outstandingPaise: 12345678, setAsidePerCyclePaise: 1234568, finalCycleAmountPaise: 1234558,
  expectedSavedByNowPaise: 4000001, savedDeltaPaise: -3000001, catchUpPaise: 3000001, status: 'BEHIND', behindSchedule: true,
};

class FakeApi {
  result: Observable<SinkingFundView> = of(behind);
  asked: { id: string; saved: number }[] = [];
  status(id: string, saved: number) { this.asked.push({ id, saved }); return this.result; }
}

describe('SinkingFundComponent', () => {
  let api: FakeApi;
  const setup = () => {
    TestBed.configureTestingModule({
      imports: [SinkingFundComponent],
      providers: [
        { provide: SinkingFundApiService, useValue: api },
        { provide: ActivatedRoute, useValue: { snapshot: { paramMap: new Map([['id', 'L1']]) } } },
      ],
    });
    const fixture = TestBed.createComponent(SinkingFundComponent);
    fixture.detectChanges();
    return { fixture, c: fixture.componentInstance, root: fixture.nativeElement as HTMLElement };
  };
  beforeEach(() => (api = new FakeApi()));

  it('shows set-aside, progress and the behind state as text from the API', () => {
    const { fixture, c, root } = setup();
    c.saved.set('10,000.01');
    c.load();
    fixture.detectChanges();
    expect(api.asked).toEqual([{ id: 'L1', saved: 1000001 }]);
    const cell = (k: string) => root.querySelector(`[data-f=${k}]`)!.textContent!.trim();
    expect(cell('state')).toBe('Behind schedule');
    expect(cell('per-cycle')).toBe('₹12,345.68');
    expect(cell('catch-up')).toBe('₹30,000.01');
    expect(cell('progress')).toBe('₹10,000.01 set aside of ₹1,23,456.78 due');
    const p = root.querySelector('[role=meter]')!;
    expect(p.getAttribute('aria-valuemax')).toBe('12345678');
    expect(p.getAttribute('aria-valuenow')).toBe('1000001');
    expect(root.querySelector('fp-card.behind')).not.toBeNull();
  });

  it('hides catch-up when on track', () => {
    api.result = of({ ...behind, status: 'ON_TRACK', behindSchedule: false, catchUpPaise: 0, savedDeltaPaise: 5 });
    const { fixture, c, root } = setup();
    c.load();
    fixture.detectChanges();
    expect(root.querySelector('[data-f=catch-up]')).toBeNull();
    expect(root.querySelector('[data-f=state]')!.textContent).toContain('On track');
    expect(root.querySelector('fp-card.behind')).toBeNull();
  });

  it('rejects bad input without calling the API', () => {
    const { fixture, c, root } = setup();
    c.saved.set('abc');
    c.load();
    fixture.detectChanges();
    expect(api.asked.length).toBe(0);
    expect(root.querySelector('[role=alert]')).not.toBeNull();
  });

  it('explains a 400 and being offline', () => {
    const { fixture, c, root } = setup();
    api.result = throwError(() => new HttpErrorResponse({ status: 400 }));
    c.load();
    fixture.detectChanges();
    expect(root.querySelector('[role=alert]')!.textContent).toContain('bullet loan');
    api.result = throwError(() => new HttpErrorResponse({ status: 0 }));
    c.load();
    fixture.detectChanges();
    expect(root.querySelector('[role=alert]')!.textContent).toContain('offline');
  });

  it('words every status', () => {
    expect(statusText('BEHIND')).toBe('Behind schedule');
    expect(statusText('FUNDED')).toBe('Fully funded');
    expect(statusText('ON_TRACK')).toBe('On track');
    expect(statusText('PAST_DUE')).toContain('due date');
  });
});
