import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { formatExactPaise } from '../expense/amount-entry';
import { GoalApiService } from '../goals/goal-api.service';
import { rupeesTextToPaiseOrZero } from '../goals/goal-format';
import { GOAL_TYPES, GoalType, GoalView } from '../goals/goal.models';
import { errorMessage, shortDate } from '../plan/plan-format';
import { PlanStepsState } from './plan-steps.state';

/** Optional wizard step: add goals (type, target, date). Monthly need and status are shown later on the Goals screen. */
@Component({
  selector: 'app-goal-step',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p class="muted">Add something you are saving for. Skip this if you are not ready; you can add goals any time under Goals. What each goal needs per month is calculated for you on the Goals screen.</p>
    <label for="goal-type">Type</label>
    <select id="goal-type" (change)="type.set(asType(val($event)))">
      @for (t of types; track t.code) {
        <option [value]="t.code" [selected]="t.code === type()">{{ t.label }}</option>
      }
    </select>
    <label for="goal-target">Target amount (rupees)</label>
    <input id="goal-target" type="text" inputmode="decimal" autocomplete="off" [value]="targetText()" (input)="targetText.set(val($event))" />
    <label for="goal-date">Target date</label>
    <input id="goal-date" type="date" [value]="date()" (input)="date.set(val($event))" />
    <button type="button" class="btn" [disabled]="!canAdd()" [attr.aria-busy]="saving()" (click)="add()">Add goal</button>
    @if (items().length > 0) {
      <ul class="rows" aria-label="Goals added">
        @for (g of items(); track g.id) {
          <li>{{ label(g.type) }}: {{ exact(g.targetAmountPaise) }} by {{ shortDate(g.targetDate) }}</li>
        }
      </ul>
    }
    <p class="error" role="alert">{{ error() }}</p>
  `,
  styleUrl: './onboarding.css',
})
export class GoalStepComponent implements OnInit {
  private readonly api = inject(GoalApiService);
  private readonly state = inject(PlanStepsState);
  readonly types = GOAL_TYPES;
  readonly exact = formatExactPaise;
  readonly shortDate = shortDate;
  readonly label = (c: GoalType) => GOAL_TYPES.find((t) => t.code === c)?.label ?? c;

  readonly items = signal<GoalView[]>([]);
  readonly type = signal<GoalType>('emergency');
  readonly targetText = signal('');
  readonly date = signal('');
  readonly saving = signal(false);
  readonly error = signal('');
  readonly targetPaise = computed(() => {
    const p = rupeesTextToPaiseOrZero(this.targetText());
    return p !== null && p > 0 ? p : null;
  });
  readonly canAdd = computed(() => this.targetPaise() !== null && /^\d{4}-\d{2}-\d{2}$/.test(this.date()) && !this.saving());

  ngOnInit(): void {
    this.reload();
  }

  asType(v: string): GoalType {
    return GOAL_TYPES.find((t) => t.code === v)?.code ?? 'emergency';
  }

  val(e: Event): string {
    return (e.target as HTMLInputElement).value;
  }

  private reload(): void {
    this.api.list().subscribe({
      next: (rows) => {
        this.items.set(rows);
        this.state.goalCount.set(rows.length);
      },
      error: (e: HttpErrorResponse) => this.error.set(errorMessage(e.status, 'Loading goals')),
    });
  }

  add(): void {
    const targetAmountPaise = this.targetPaise();
    if (targetAmountPaise === null || !this.canAdd()) return;
    this.saving.set(true);
    this.error.set('');
    this.api.create({ type: this.type(), targetAmountPaise, targetDate: this.date(), currentAmountPaise: 0, priority: 5 }).subscribe({
      next: () => {
        this.saving.set(false);
        this.targetText.set('');
        this.date.set('');
        this.reload();
      },
      error: (e: HttpErrorResponse) => {
        this.saving.set(false);
        this.error.set(errorMessage(e.status, 'Saving the goal'));
      },
    });
  }
}
