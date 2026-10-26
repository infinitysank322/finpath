import { Routes } from '@angular/router';
import { OnboardingComponent } from './onboarding.component';
import { ONBOARDING_STEP_PROVIDERS } from './onboarding.steps';

/** Lazy child routes: the step registry is provided here so it loads with the wizard, not the main bundle. */
export const ONBOARDING_ROUTES: Routes = [
  { path: '', providers: [...ONBOARDING_STEP_PROVIDERS], component: OnboardingComponent, title: 'Set up - FinPath' },
];
