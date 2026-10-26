import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { forkJoin, of } from 'rxjs';
import { catchError, map, switchMap } from 'rxjs/operators';
import { AllocationApiService } from '../allocation/allocation-api.service';
import { formatExactPaise } from '../expense/amount-entry';
import { PaiseInrPipe } from '../paise-inr.pipe';
import { errorMessage, paiseToRupeesText, shortDate } from '../plan/plan-format';
import { GoalApiService } from './goal-api.service';
import { progressPercent, rupeesTextToPaiseOrZero } from './goal-format';
import { GOAL_STATUS_TEXT, GOAL_TYPES, GoalStatusView, GoalType, GoalView } from './goal.models';

export interface GoalRow {
  goal: GoalView;
  status: GoalStatusView;
  percent: number;
}

@Component({
  selector: 'app-goals',
  standalone: true,
  imports: [PaiseInrPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './goals.component.html',
  styleUrl: './goals.css',
})
export class GoalsComponent implements OnInit {
  private readonly api = inject(GoalApiService);
  private readonly allocation = inject(AllocationApiService);

  readonly types = GOAL_TYPES;
  readonly exact = formatExactPaise;
  readonly shortDate = shortDate;
  readonly statusText = GOAL_STATUS_TEXT;
  readonly typeLabel = (c: GoalType) => GOAL_TYPES.find((t) => t.code === c)?.label ?? c;

  readonly rows = signal<GoalRow[]>([]);
  readonly loading = signal(true);
  readonly error = signal('');
  readonly message = signal('');
  /** True when the cycle allocation could not be read, so every plan figure below is the API's zero. */
  readonly planUnavailable = signal(false);

  readonly formOpen = signal(false);
  readonly editingId = signal<string | null>(null);
  readonly type = signal<GoalType>('emergency');
  readonly targetText = signal('');
  readonly currentText = signal('');
  readonly targetDate = signal('');
  readonly priority = signal('5');
  readonly saving = signal(false);
  readonly confirmingId = signal<string | null>(null);
  readonly busy = signal(false);

  readonly targetPaise = computed(() => {
    const p = rupeesTextToPaiseOrZero(this.targetText());
    return p !== null && p > 0 ? p : null;
  });
  readonly currentPaise = computed(() => (this.currentText().trim() === '' ? 0 : rupeesTextToPaiseOrZero(this.currentText())));
  readonly priorityValue = computed(() => {
    const t = this.priority().trim();
    if (!/^\d{1,2}$/.test(t)) return null;
    const n = parseInt(t, 10);
    return n >= 1 && n <= 10 ? n : null;
  });
  readonly canSave = computed(
    () =>
      this.targetPaise() !== null &&
      this.currentPaise() !== null &&
      this.priorityValue() !== null &&
      /^\d{4}-\d{2}-\d{2}$/.test(this.targetDate()) &&
      !this.saving(),
  );

  ngOnInit(): void {
    this.reload();
  }

  reload(): void {
    this.loading.set(true);
    this.error.set('');
    this.planUnavailable.set(false);
    forkJoin({
      goals: this.api.list(),
      // The per-goal monthly plan is the allocation's SAFE_GOALS line (ref = goal id). No allocation yet is not fatal.
      plan: this.allocation.current().pipe(
        map((a) => a.lines),
        catchError(() => {
          this.planUnavailable.set(true);
          return of([]);
        }),
      ),
    })
      .pipe(
        switchMap(({ goals, plan }) =>
          goals.length === 0
            ? of([] as GoalRow[])
            : forkJoin(
                goals.map((g) => {
                  const planned = plan.find((l) => l.step === 'SAFE_GOALS' && l.ref === g.id)?.amountPaise ?? 0;
                  return this.api.status(g.id, planned).pipe(
                    map((status): GoalRow => ({ goal: g, status, percent: progressPercent(g.currentAmountPaise, g.targetAmountPaise) })),
                  );
                }),
              ),
        ),
      )
      .subscribe({
        next: (rows) => {
          this.rows.set(rows);
          this.loading.set(false);
        },
        error: (e: HttpErrorResponse) => {
          this.rows.set([]);
          this.fail(e, 'Loading goals');
        },
      });
  }

  openAdd(): void {
    this.editingId.set(null);
    this.type.set('emergency');
    this.targetText.set('');
    this.currentText.set('');
    this.targetDate.set('');
    this.priority.set('5');
    this.formOpen.set(true);
    this.message.set('');
    this.confirmingId.set(null);
  }

  openEdit(g: GoalView): void {
    this.editingId.set(g.id);
    this.type.set(g.type);
    this.targetText.set(paiseToRupeesText(g.targetAmountPaise));
    this.currentText.set(g.currentAmountPaise === 0 ? '' : paiseToRupeesText(g.currentAmountPaise));
    this.targetDate.set(g.targetDate);
    this.priority.set(String(g.priority));
    this.formOpen.set(true);
    this.message.set('');
    this.confirmingId.set(null);
  }

  cancel(): void {
    this.formOpen.set(false);
  }

  on(ref: { set(v: string): void }, event: Event): void {
    ref.set((event.target as HTMLInputElement).value);
  }

  save(): void {
    const targetAmountPaise = this.targetPaise();
    const currentAmountPaise = this.currentPaise();
    const priority = this.priorityValue();
    if (targetAmountPaise === null || currentAmountPaise === null || priority === null || !this.canSave()) return;
    const body = { type: this.type(), targetAmountPaise, targetDate: this.targetDate(), currentAmountPaise, priority };
    this.saving.set(true);
    this.error.set('');
    const id = this.editingId();
    (id ? this.api.replace(id, body) : this.api.create(body)).subscribe({
      next: () => {
        this.saving.set(false);
        this.formOpen.set(false);
        this.message.set('Goal saved.');
        this.reload();
      },
      error: (e: HttpErrorResponse) => this.fail(e, 'Saving the goal'),
    });
  }

  askRemove(g: GoalView): void {
    this.confirmingId.set(g.id);
  }

  keep(): void {
    this.confirmingId.set(null);
  }

  remove(g: GoalView): void {
    if (this.busy()) return;
    this.busy.set(true);
    this.api.remove(g.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.confirmingId.set(null);
        this.message.set('Goal removed.');
        this.reload();
      },
      error: (e: HttpErrorResponse) => this.fail(e, 'Removing the goal'),
    });
  }

  private fail(e: HttpErrorResponse, action: string): void {
    this.loading.set(false);
    this.saving.set(false);
    this.busy.set(false);
    this.confirmingId.set(null);
    this.error.set(errorMessage(e.status, action));
  }
}
