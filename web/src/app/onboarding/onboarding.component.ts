import { NgComponentOutlet } from '@angular/common';
import {
  ChangeDetectionStrategy, Component, ElementRef, Injector, afterNextRender, computed, effect, inject, runInInjectionContext, signal, viewChild,
} from '@angular/core';
import { Router } from '@angular/router';
import { AUTH_STORAGE, AuthSessionStore } from '../auth/session.store';
import { ConsentService } from './consent.service';
import { clearPosition, loadPosition, savePosition } from './wizard-progress';
import { CONSENT_STEP_ID, ONBOARDING_DONE_PATH, ONBOARDING_STEP, OnboardingStep } from './wizard-step';

export const FINISH_NEEDS_CONSENT = 'Consent is needed before you can finish. Please give it on this screen.';
export const FINISH_CHECK_FAILED = 'We could not confirm your consent. Check your connection and try again.';

@Component({
  selector: 'app-onboarding',
  standalone: true,
  imports: [NgComponentOutlet],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './onboarding.component.html',
  styleUrl: './onboarding.css',
})
export class OnboardingComponent {
  private readonly injector = inject(Injector);
  private readonly router = inject(Router);
  private readonly storage = inject(AUTH_STORAGE);
  private readonly userId = inject(AuthSessionStore).userId;
  private readonly consent = inject(ConsentService);
  private readonly heading = viewChild<ElementRef<HTMLElement>>('heading');

  readonly steps: OnboardingStep[] = [...(inject(ONBOARDING_STEP, { optional: true }) as unknown as OnboardingStep[] | null ?? [])].sort(
    (a, b) => a.order - b.order,
  );
  private readonly readyBy = new Map<string, () => boolean>(
    this.steps.map((s) => [s.id, s.ready ? runInInjectionContext(this.injector, s.ready) : () => true]),
  );

  readonly index = signal(this.initialIndex());
  readonly step = computed(() => this.steps[this.index()]);
  readonly total = this.steps.length;
  readonly isLast = computed(() => this.index() === this.total - 1);
  readonly progressText = computed(() => this.total === 0 ? '' : `Step ${this.index() + 1} of ${this.total}`);
  readonly percent = computed(() => this.total === 0 ? 0 : Math.round(((this.index() + 1) / this.total) * 100));
  readonly canNext = computed(() => this.step() ? (this.readyBy.get(this.step().id)?.() ?? true) : false);
  readonly message = signal('');
  readonly finishing = signal(false);

  constructor() {
    effect(() => {
      const i = this.index();
      const id = this.steps[i]?.id;
      if (id) savePosition(this.storage, this.userId(), id, i);
      afterNextRender(() => this.heading()?.nativeElement.focus(), { injector: this.injector });
    });
  }

  private initialIndex(): number {
    if (this.steps.length === 0) return 0;
    const saved = loadPosition(this.storage, this.userId());
    if (!saved) return 0;
    const byId = this.steps.findIndex((s) => s.id === saved.id);
    const i = byId >= 0 ? byId : saved.index;
    return Math.min(Math.max(i, 0), this.steps.length - 1);
  }

  back(): void {
    this.message.set('');
    if (this.index() > 0) this.index.update((i) => i - 1);
  }

  next(): void {
    if (!this.canNext() || this.isLast()) return;
    this.message.set('');
    this.index.update((i) => i + 1);
  }

  /** Finishing re-asks the server; local state alone never lets the wizard complete. */
  async finish(): Promise<void> {
    if (this.finishing() || !this.canNext()) return;
    this.finishing.set(true);
    this.message.set('');
    try {
      const failedCheck = !(await this.consent.refresh()) && this.consent.error() !== '';
      if (failedCheck) {
        this.message.set(FINISH_CHECK_FAILED);
        return;
      }
      if (!this.consent.given()) {
        this.message.set(FINISH_NEEDS_CONSENT);
        const at = this.steps.findIndex((s) => s.id === CONSENT_STEP_ID);
        if (at >= 0) this.index.set(at);
        return;
      }
      clearPosition(this.storage, this.userId());
      await this.router.navigateByUrl(ONBOARDING_DONE_PATH);
    } finally {
      this.finishing.set(false);
    }
  }
}
