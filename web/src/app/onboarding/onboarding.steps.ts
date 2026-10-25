import { Provider, inject } from '@angular/core';
import { ConsentStepComponent } from './consent-step.component';
import { ConsentService } from './consent.service';
import { DoneStepComponent } from './done-step.component';
import { CONSENT_STEP_ID, ONBOARDING_STEP, OnboardingStep } from './wizard-step';

const step = (s: OnboardingStep): Provider => ({ provide: ONBOARDING_STEP, multi: true, useValue: s });

/**
 * The registered steps. T47 (cycle day, income, fixed expenses), T55 (debts), T66 (goals) and T69 (assets)
 * append a `step({...})` here with an `order` between 10 and 1000.
 */
export const ONBOARDING_STEP_PROVIDERS: Provider[] = [
  step({
    id: CONSENT_STEP_ID,
    order: 10,
    question: 'Can we keep your plan on our servers?',
    component: ConsentStepComponent,
    ready: () => inject(ConsentService).given,
  }),
  step({ id: 'done', order: 1000, question: "You're set", component: DoneStepComponent }),
];
