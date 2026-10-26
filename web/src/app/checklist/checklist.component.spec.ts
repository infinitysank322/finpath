import { HttpErrorResponse } from '@angular/common/http';
import { TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { expectFitsPhone } from '../ui/ui-testing';
import { ChecklistApiService } from './checklist-api.service';
import { cellLabel, cycleLabel } from './checklist-format';
import { ChecklistComponent } from './checklist.component';
import { ChecklistItem, ChecklistView, StreakView } from './checklist.models';

const item = (id: string, done: boolean, over: Partial<ChecklistItem> = {}): ChecklistItem => ({
  id, type: 'EMI', sourceRef: 'r' + id, label: 'Loan ' + id, amountPaise: 1234500, done, doneAt: done ? '2026-10-26T00:00:00Z' : null, ...over,
});

class FakeApi {
  view: ChecklistView = { cycleStart: '2026-10-25', items: [item('a', true), item('b', false, { type: 'SIP', amountPaise: 500000 })], doneCount: 1, totalCount: 2 };
  streakView: StreakView = {
    streak: 7,
    history: [
      { cycleStart: '2026-08-25', done: true },
      { cycleStart: '2026-09-25', done: false },
    ],
  };
  ticked: string[] = [];
  generated = 0;
  current(): Observable<ChecklistView> { return of(this.view); }
  generate(): Observable<ChecklistView> { this.generated++; return of({ ...this.view, items: [item('n', false)], totalCount: 1, doneCount: 0 }); }
  markDone(id: string): Observable<ChecklistItem> {
    this.ticked.push(id);
    this.view = { ...this.view, items: this.view.items.map((i) => (i.id === id ? { ...i, done: true } : i)), doneCount: 2 };
    this.streakView = { ...this.streakView, streak: 8 };
    return of(item(id, true));
  }
  streak(): Observable<StreakView> { return of(this.streakView); }
}

describe('ChecklistComponent', () => {
  let api: FakeApi;
  const setup = () => {
    TestBed.configureTestingModule({ imports: [ChecklistComponent], providers: [{ provide: ChecklistApiService, useValue: api }] });
    const fixture = TestBed.createComponent(ChecklistComponent);
    fixture.detectChanges();
    return { fixture, root: fixture.nativeElement as HTMLElement };
  };
  beforeEach(() => (api = new FakeApi()));

  it('shows the API streak and counts as given', () => {
    const { root } = setup();
    expect(root.querySelector('[data-f=streak]')!.textContent).toContain('7');
    expect(root.querySelector('[data-f=progress]')!.textContent).toContain('1 of 2 done');
    expect(root.textContent).toContain('₹12,345');
  });

  it('labels the cycle by start date, not a month name, unless it starts on the 1st', () => {
    expect(cycleLabel('2026-10-25')).toBe('Cycle from 25/10/2026');
    expect(cycleLabel('2026-10-01')).toBe('Oct 2026');
    expect(cellLabel('2026-10-25')).toBe('25/10');
    const { root } = setup();
    expect(root.textContent).not.toMatch(/Oct|Sep|Aug/);
  });

  it('heatmap cells carry screen-reader text and a non-colour cue (glyph and word)', () => {
    const { root } = setup();
    const cells = root.querySelectorAll('[data-f=cell]');
    expect(cells.length).toBe(2);
    expect(cells[0].querySelector('.sr-only')!.textContent).toContain('Cycle from 25/08/2026: every item done');
    expect(cells[1].querySelector('.sr-only')!.textContent).toContain('Cycle from 25/09/2026: not every item done');
    expect(cells[0].textContent).toContain('✓');
    expect(cells[0].textContent).toContain('Done');
    expect(cells[1].textContent).toContain('✕');
    expect(cells[1].textContent).toContain('Missed');
  });

  it('done items show text and a tick, open items a named button', () => {
    const { root } = setup();
    const rows = root.querySelectorAll('[data-f=item]');
    expect(rows[0].querySelector('[data-f=done-text]')!.textContent).toContain('Done');
    expect(rows[1].querySelector('[data-f=tick]')!.textContent).toContain('Loan b');
  });

  it('ticking posts, then shows the API counts and streak', () => {
    const { fixture, root } = setup();
    (root.querySelector('[data-f=tick]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(api.ticked).toEqual(['b']);
    expect(root.querySelector('[data-f=progress]')!.textContent).toContain('2 of 2 done');
    expect(root.querySelector('[data-f=streak]')!.textContent).toContain('8');
    expect(root.querySelector('[data-f=tick]')).toBeNull();
  });

  it('an empty cycle offers to create the checklist', () => {
    api.view = { cycleStart: '2026-10-25', items: [], doneCount: 0, totalCount: 0 };
    const { fixture, root } = setup();
    expect(root.querySelector('[data-f=empty]')).not.toBeNull();
    (root.querySelector('[data-f=generate]') as HTMLButtonElement).click();
    fixture.detectChanges();
    expect(api.generated).toBe(1);
    expect(root.querySelectorAll('[data-f=item]').length).toBe(1);
  });

  it('a failed load shows an alert', () => {
    api.current = () => throwError(() => new HttpErrorResponse({ status: 403 }));
    const { root } = setup();
    expect(root.querySelector('[role=alert]')!.textContent).toContain('Consent');
  });

  it('fits 380 px', () => {
    const { root } = setup();
    expectFitsPhone(root, 'checklist');
  });
});
