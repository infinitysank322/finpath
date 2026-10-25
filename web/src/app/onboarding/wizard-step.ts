import { InjectionToken, Signal, Type } from '@angular/core';

/**
 * One question of the onboarding wizard. Later tasks (cycle day/income, debts, goals, assets) add a step by
 * providing ONBOARDING_STEP with `multi: true`; the shell never needs editing.
 */
export interface OnboardingStep {
  /** Stable id, saved for resume. Never reuse an id for a different question. */
  id: string;
  /** Sort key. Built-ins: consent 10, final 1000. Pick a number between to slot in. */
  order: number;
  /** The one question, shown as the screen heading. */
  question: string;
  /** Body of the screen. Rendered inside the shell; may inject anything. */
  component: Type<unknown>;
  /** Runs in an injection context. While it returns false, Next/Finish stay disabled. Default: always ready. */
  ready?: () => Signal<boolean>;
}

export const ONBOARDING_STEP = new InjectionToken<OnboardingStep>('ONBOARDING_STEP');

/** Where the wizard goes after a successful finish. */
export const ONBOARDING_DONE_PATH = '/home';
export const CONSENT_STEP_ID = 'consent';
