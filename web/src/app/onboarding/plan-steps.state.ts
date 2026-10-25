import { Injectable, computed, signal } from '@angular/core';

/** What the plan steps have confirmed with the API; the wizard's Next buttons read it. Holds no amounts. */
@Injectable({ providedIn: 'root' })
export class PlanStepsState {
  /** The cycle day on screen equals the one the server holds. */
  readonly cycleDaySaved = signal(false);
  readonly incomeCount = signal(0);
  /** Goals the user has added in the wizard; the step is optional so nothing gates on it. */
  readonly goalCount = signal(0);
  /** Holdings the user has entered in the wizard; the step is optional so nothing gates on it. */
  readonly holdingCount = signal(0);
  readonly incomeReady = computed(() => this.incomeCount() > 0);
}
