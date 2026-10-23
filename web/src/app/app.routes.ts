import { Routes } from '@angular/router';

const page = () => import('./placeholder.component').then((m) => m.PlaceholderComponent);

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'home' },
  { path: 'home', loadComponent: page, title: 'Home - FinPath', data: { heading: 'Home' } },
  {
    path: 'spending/add',
    loadComponent: () => import('./expense/add-expense.component').then((m) => m.AddExpenseComponent),
    title: 'Add expense - FinPath',
  },
  { path: 'spending/budget', loadComponent: () => import('./budget/budget.component').then((m) => m.BudgetComponent), title: 'Budget - FinPath' },
  { path: 'spending', loadComponent: page, title: 'Spending - FinPath', data: { heading: 'Spending' } },
  { path: 'debt/payoff', loadComponent: () => import('./debt/payoff.component').then((m) => m.PayoffComponent), title: 'Payoff timeline - FinPath' },
  { path: 'debt/loans/:id/interest', loadComponent: () => import('./debt/loan-interest.component').then((m) => m.LoanInterestComponent), title: 'Principal vs interest - FinPath' },
  { path: 'debt/loans/:id/prepay', loadComponent: () => import('./debt/prepay.component').then((m) => m.PrepayComponent), title: 'Prepayment simulator - FinPath' },
  { path: 'debt/emi-guard', loadComponent: () => import('./debt/emi-guard.component').then((m) => m.EmiGuardComponent), title: 'EMI to income - FinPath' },
  { path: 'debt', loadComponent: page, title: 'Debt - FinPath', data: { heading: 'Debt' } },
  { path: 'goals', loadComponent: page, title: 'Goals - FinPath', data: { heading: 'Goals' } },
  { path: 'more', pathMatch: 'full', loadComponent: () => import('./plan/more.component').then((m) => m.MoreComponent), title: 'More - FinPath' },
  { path: 'more/income', loadComponent: () => import('./plan/income.component').then((m) => m.IncomeComponent), title: 'Income sources - FinPath' },
  { path: 'more/recurring', loadComponent: () => import('./plan/recurring.component').then((m) => m.RecurringComponent), title: 'Recurring bills - FinPath' },
  { path: '**', redirectTo: 'home' },
];
