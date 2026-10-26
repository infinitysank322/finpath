import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { SpendingApiService } from './spending-api.service';
import { SpendingComponent } from './spending.component';
import { CategoryBreakdown, SpendingComparison, TagSummary } from './spending.models';

// Neutral synthetic data: not any persona's figures.
const tags: TagSummary = { from: '2026-10-25', toExclusive: '2026-11-25', needPaise: 123400, wantPaise: 56700, wastePaise: 8900, untaggedPaise: 1200, totalPaise: 190200 };
const categories: CategoryBreakdown = {
  cycleStart: '2026-10-25', cycleEndExclusive: '2026-11-25', totalPaise: 190200,
  lines: [{ categoryCode: 'GROCERIES', spentPaise: 123400, share: '0.6488' }, { categoryCode: 'DINING', spentPaise: 66800, share: '0.3512' }],
};
const comparison: SpendingComparison = {
  cycleStart: '2026-10-25', cycleEndExclusive: '2026-11-25',
  baselineCycles: [{ start: '2026-07-25', endExclusive: '2026-08-25' }, { start: '2026-08-25', endExclusive: '2026-09-25' }, { start: '2026-09-25', endExclusive: '2026-10-25' }],
  lines: [
    { categoryCode: 'GROCERIES', currentPaise: 123400, averagePaise: 100000, deltaPaise: 23400 },
    { categoryCode: 'DINING', currentPaise: 66800, averagePaise: 70000, deltaPaise: -3200 },
  ],
};

class FakeApi {
  tagResult: Observable<TagSummary> = of(tags);
  catResult: Observable<CategoryBreakdown> = of(categories);
  compResult: Observable<SpendingComparison> = of(comparison);
  tagSummary() { return this.tagResult; }
  categories() { return this.catResult; }
  comparison() { return this.compResult; }
}

describe('SpendingComponent', () => {
  let api: FakeApi;
  const setup = () => {
    TestBed.configureTestingModule({ imports: [SpendingComponent], providers: [{ provide: SpendingApiService, useValue: api }] });
    const fixture = TestBed.createComponent(SpendingComponent);
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement };
  };
  const cells = (root: HTMLElement, f: string) =>
    Array.from(root.querySelectorAll(`[data-f=${f}] tbody tr`)).map((r) => Array.from(r.children).map((c) => c.textContent!.trim()).join(' | '));
  beforeEach(() => (api = new FakeApi()));

  it('gives the donut a table equivalent with INR amounts and the API share', () => {
    const { root } = setup();
    expect(cells(root, 'donut-table')).toEqual(['Groceries | ₹1,234 | 64.88%', 'Dining | ₹668 | 35.12%']);
  });

  it('shows need, want and waste with a non-colour text label per tag', () => {
    const { root } = setup();
    expect(cells(root, 'tag-table')).toEqual(['Need | ₹1,234', 'Want | ₹567', 'Waste | ₹89', 'Not tagged | ₹12']);
    expect(root.querySelector('[data-f=tag-strip]')!.textContent).toContain('Waste');
  });

  it('gives the comparison a table with average and worded change', () => {
    const { root } = setup();
    expect(cells(root, 'comparison-table')).toEqual(['Groceries | ₹1,234 | ₹1,000 | Up ₹234', 'Dining | ₹668 | ₹700 | Down ₹32']);
  });

  it('labels cycles by dates and never by a month name', () => {
    const { root } = setup();
    const text = root.textContent!;
    expect(text).toContain('25/10/2026 to 24/11/2026');
    expect(text).toContain('25/07/2026 to 24/10/2026');
    expect(text).not.toMatch(/January|February|March|April|May|June|July|August|September|October|November|December/);
  });

  it('names a month only when the cycle starts on the 1st', () => {
    api.catResult = of({ ...categories, cycleStart: '2026-10-01', cycleEndExclusive: '2026-11-01' });
    const { root } = setup();
    expect(root.querySelector('[data-f=cycle]')!.textContent).toContain('October 2026');
  });

  it('puts an aria description on each chart and hides the canvas from assistive tech', () => {
    const { root } = setup();
    const donut = root.querySelector('[data-f=donut]')!;
    expect(donut.getAttribute('role')).toBe('img');
    expect(donut.getAttribute('aria-label')).toContain('Groceries 64.88 percent');
    const bars = root.querySelector('[data-f=comparison-chart]')!;
    expect(bars.getAttribute('role')).toBe('img');
    expect(bars.getAttribute('aria-label')).toContain('Dining: ₹668 against an average of ₹700');
    expect(root.querySelectorAll('.fp-chart-canvas[aria-hidden=true]').length).toBe(2);
  });

  it('shows the educational label and the assumptions beside the comparison', () => {
    const { root } = setup();
    expect(root.textContent).toContain('Educational');
    expect(root.textContent).toContain('Assumptions');
  });

  it('shows an empty state, not a blank chart, when nothing was spent', () => {
    api.catResult = of({ ...categories, totalPaise: 0, lines: [] });
    api.compResult = of({ ...comparison, lines: [] });
    const { root } = setup();
    expect(root.querySelector('[data-f=empty]')).not.toBeNull();
    expect(root.querySelector('[data-f=comparison-empty]')).not.toBeNull();
    expect(root.querySelector('[data-f=donut]')).toBeNull();
  });

  it('keeps the other sections when only the comparison fails', () => {
    api.compResult = throwError(() => new HttpErrorResponse({ status: 500 }));
    const { root } = setup();
    expect(root.querySelector('[data-f=comparison-error]')!.textContent).toContain('server had a problem');
    expect(cells(root, 'donut-table').length).toBe(2);
  });

  it('shows the plan-cycle message on a 404 from the categories call', () => {
    api.catResult = throwError(() => new HttpErrorResponse({ status: 404 }));
    const { root } = setup();
    expect(root.querySelector('[role=alert]')!.textContent).toContain('Set up your plan cycle first.');
    expect(root.querySelector('[role=status]')).toBeNull();
  });
});
