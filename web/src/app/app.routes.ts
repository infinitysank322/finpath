import { Route, Routes } from '@angular/router';
import { authGuard, signedOutGuard } from './auth/auth.guard';

const page = () => import('./placeholder.component').then((m) => m.PlaceholderComponent);

/** Everything behind sign-in. Redirect routes cannot carry guards; their targets do. */
const appRoutes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'home' },
  { path: 'onboarding', loadChildren: () => import('./onboarding/onboarding.routes').then((m) => m.ONBOARDING_ROUTES) },
  { path: 'home', loadComponent: () => import('./dashboard/home.component').then((m) => m.HomeComponent), title: 'Home - FinPath' },
  {
    path: 'spending/add',
    loadComponent: () => import('./expense/add-expense.component').then((m) => m.AddExpenseComponent),
    title: 'Add expense - FinPath',
  },
  { path: 'spending/budget', loadComponent: () => import('./budget/budget.component').then((m) => m.BudgetComponent), title: 'Budget - FinPath' },
  { path: 'spending', loadComponent: page, title: 'Spending - FinPath', data: { heading: 'Spending' } },
  { path: 'debt/payoff', loadComponent: () => import('./debt/payoff.component').then((m) => m.PayoffComponent), title: 'Payoff timeline - FinPath' },
  { path: 'debt/loans', pathMatch: 'full', loadComponent: () => import('./debt/loan-list.component').then((m) => m.LoanListComponent), title: 'Loans - FinPath' },
  { path: 'debt/loans/:id', pathMatch: 'full', loadComponent: () => import('./debt/loan-detail.component').then((m) => m.LoanDetailComponent), title: 'Loan - FinPath' },
  { path: 'debt/loans/:id/interest', loadComponent: () => import('./debt/loan-interest.component').then((m) => m.LoanInterestComponent), title: 'Principal vs interest - FinPath' },
  { path: 'debt/loans/:id/prepay', loadComponent: () => import('./debt/prepay.component').then((m) => m.PrepayComponent), title: 'Prepayment simulator - FinPath' },
  { path: 'debt/loans/:id/sinking-fund', loadComponent: () => import('./debt/sinking-fund.component').then((m) => m.SinkingFundComponent), title: 'Sinking fund - FinPath' },
  { path: 'debt/emi-guard', loadComponent: () => import('./debt/emi-guard.component').then((m) => m.EmiGuardComponent), title: 'EMI to income - FinPath' },
  { path: 'debt', pathMatch: 'full', loadComponent: () => import('./debt/debt-hub.component').then((m) => m.DebtHubComponent), title: 'Debt - FinPath' },
  { path: 'goals/fi-planner', loadComponent: () => import('./fi/fi-planner.component').then((m) => m.FiPlannerComponent), title: 'FI planner - FinPath' },
  { path: 'more/allocation', loadComponent: () => import('./allocation/allocation.component').then((m) => m.AllocationComponent), title: 'Cycle allocation - FinPath' },
  { path: 'goals', pathMatch: 'full', loadComponent: () => import('./goals/goals.component').then((m) => m.GoalsComponent), title: 'Goals - FinPath' },
  { path: 'more/investments', loadComponent: () => import('./investments/investments.component').then((m) => m.InvestmentsComponent), title: 'Investments - FinPath' },
  { path: 'more', pathMatch: 'full', loadComponent: () => import('./plan/more.component').then((m) => m.MoreComponent), title: 'More - FinPath' },
  { path: 'more/checklist', loadComponent: () => import('./checklist/checklist.component').then((m) => m.ChecklistComponent), title: 'Checklist - FinPath' },
  { path: 'more/income', loadComponent: () => import('./plan/income.component').then((m) => m.IncomeComponent), title: 'Income sources - FinPath' },
  { path: 'more/recurring', loadComponent: () => import('./plan/recurring.component').then((m) => m.RecurringComponent), title: 'Recurring bills - FinPath' },
  { path: '**', redirectTo: 'home' },
];

const guarded = (r: Route): Route => (r.redirectTo !== undefined ? r : { ...r, canActivate: [authGuard] });

/** Paths that render without the app chrome (bottom nav, Add expense). */
export const AUTH_PATHS = ['/sign-in', '/auth/callback'];

export const routes: Routes = [
  {
    path: 'sign-in',
    canActivate: [signedOutGuard],
    loadComponent: () => import('./auth/sign-in.component').then((m) => m.SignInComponent),
    title: 'Sign in - FinPath',
  },
  {
    path: 'auth/callback',
    loadComponent: () => import('./auth/auth-callback.component').then((m) => m.AuthCallbackComponent),
    title: 'Signing in - FinPath',
  },
  ...appRoutes.map(guarded),
];
