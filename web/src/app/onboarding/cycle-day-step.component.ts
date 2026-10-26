import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, OnInit, computed, inject, signal } from '@angular/core';
import { PlanApiService } from '../plan/plan-api.service';
import { errorMessage, shortDate } from '../plan/plan-format';
import { PlanStepsState } from './plan-steps.state';

/** Wizard default if the server cannot be read; the API's own default for a new user is also 25. */
export const DEFAULT_CYCLE_DAY = 25;

@Component({
  selector: 'app-cycle-day-step',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <p class="muted">Pick the day of the month your salary usually arrives. Your budget month runs from that day.</p>
    <label for="cycle-day">Cycle start day (1 to 31)</label>
    <input id="cycle-day" type="number" inputmode="numeric" min="1" max="31" step="1" [value]="day()" (input)="onInput($event)" [attr.aria-invalid]="!valid()" />
    @if (!valid()) {
      <p class="error" role="alert">Enter a whole number from 1 to 31.</p>
    }
    <button type="button" class="btn" [disabled]="!valid() || saving() || state.cycleDaySaved()" (click)="save()">
      {{ state.cycleDaySaved() ? 'Saved' : 'Save day' }}
    </button>
    @if (cycleStart(); as c) {
      <p class="muted small" role="status">Current cycle starts {{ shortDate(c) }}.</p>
    }
    <p class="error" role="alert">{{ error() }}</p>
  `,
  styleUrl: './onboarding.css',
})
export class CycleDayStepComponent implements OnInit {
  private readonly api = inject(PlanApiService);
  readonly state = inject(PlanStepsState);
  readonly shortDate = shortDate;

  readonly day = signal<number | null>(DEFAULT_CYCLE_DAY);
  readonly cycleStart = signal('');
  readonly saving = signal(false);
  readonly error = signal('');
  readonly valid = computed(() => {
    const d = this.day();
    return d !== null && Number.isInteger(d) && d >= 1 && d <= 31;
  });
  private savedDay: number | null = null;
  private edited = false;

  ngOnInit(): void {
    this.api.cycleCurrent().subscribe({
      next: (c) => {
        this.savedDay = c.startDay;
        if (!this.edited) this.day.set(c.startDay); // never overwrite what the user already typed
        this.cycleStart.set(c.cycleStart);
        this.state.cycleDaySaved.set(this.day() === c.startDay);
      },
      error: (e: HttpErrorResponse) => this.error.set(errorMessage(e.status, 'Loading your cycle day')),
    });
  }

  onInput(event: Event): void {
    this.edited = true;
    const raw = (event.target as HTMLInputElement).value;
    this.day.set(raw === '' ? null : Number(raw));
    this.state.cycleDaySaved.set(this.valid() && this.day() === this.savedDay);
  }

  save(): void {
    const d = this.day();
    if (d === null || !this.valid() || this.saving()) return;
    this.saving.set(true);
    this.error.set('');
    this.api.cycleChangeStartDay(d).subscribe({
      next: (c) => {
        this.saving.set(false);
        this.savedDay = c.startDay;
        this.cycleStart.set(c.cycleStart);
        this.state.cycleDaySaved.set(true);
      },
      error: (e: HttpErrorResponse) => {
        this.saving.set(false);
        this.error.set(errorMessage(e.status, 'Saving your cycle day'));
      },
    });
  }
}
