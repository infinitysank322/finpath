import { Provider, inject } from '@angular/core';
import { CycleDayStepComponent } from './cycle-day-step.component';
import { FixedExpensesStepComponent } from './fixed-expenses-step.component';
import { GoalStepComponent } from './goal-step.component';
import { AssetsStepComponent } from './assets-step.component';
import { IncomeStepComponent } from './income-step.component';
import { PlanStepsState } from './plan-steps.state';
import { ConsentStepComponent } from './consent-step.component';
import { ConsentService } from './consent.service';
import { DoneStepComponent } from './done-step.component';
import { CONSENT_STEP_ID, ONBOARDING_STEP, OnboardingStep } from './wizard-step';

const step = (s: OnboardingStep): Provider => ({ provide: ONBOARDING_STEP, multi: true, useValue: s });

/**
 * The registered steps. T47 (cycle day, income, fixed expenses), T55 (debts), T66 (goals) and T69 (assets)
 * append a `step({...})` here with an `order` between 10 and 1000.
 */
export const SHELL_STEP_PROVIDERS: Provider[] = [
  step({
    id: CONSENT_STEP_ID,
    order: 10,
    question: 'Can we keep your plan on our servers?',
    component: ConsentStepComponent,
    ready: () => inject(ConsentService).given,
  }),
  step({ id: 'done', order: 1000, question: "You're set", component: DoneStepComponent }),
];

/** T47: cycle day (default 25), income, fixed expenses. Each talks to the API; none does money maths. */
export const PLAN_STEP_PROVIDERS: Provider[] = [
  step({ id: 'cycle-day', order: 20, question: 'Which day does your month start?', component: CycleDayStepComponent, ready: () => inject(PlanStepsState).cycleDaySaved }),
  step({ id: 'income', order: 30, question: 'What do you earn each month?', component: IncomeStepComponent, ready: () => inject(PlanStepsState).incomeReady }),
  step({ id: 'fixed-expenses', order: 40, question: 'Which bills repeat every month?', component: FixedExpensesStepComponent }),
];

/** T66: an optional goal step (skippable; goals can be added any time under Goals). */
export const GOAL_STEP_PROVIDERS: Provider[] = [
  step({ id: 'goals', order: 60, question: 'What are you saving for?', component: GoalStepComponent }),
];

/** T69: an optional assets step with an explicit EPF balance (OQ-1: asked for, never derived). */
export const ASSET_STEP_PROVIDERS: Provider[] = [
  step({ id: 'assets', order: 70, question: 'What do you already have invested?', component: AssetsStepComponent }),
];

export const ONBOARDING_STEP_PROVIDERS: Provider[] = [...SHELL_STEP_PROVIDERS, ...PLAN_STEP_PROVIDERS, ...GOAL_STEP_PROVIDERS, ...ASSET_STEP_PROVIDERS];
