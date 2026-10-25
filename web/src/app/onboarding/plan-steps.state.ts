import { Injectable, computed, signal } from '@angular/core';

/** What the plan steps have confirmed with the API; the wizard's Next buttons read it. Holds no amounts. */
@Injectable({ providedIn: 'root' })
export class PlanStepsState {
  /** The cycle day on screen equals the one the server holds. */
  readonly cycleDaySaved = signal(false);
  readonly incomeCount = signal(0);
  readonly loanCount = signal(0);
  readonly incomeReady = computed(() => this.incomeCount() > 0);
}
