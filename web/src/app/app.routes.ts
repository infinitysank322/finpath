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
  { path: 'spending', loadComponent: page, title: 'Spending - FinPath', data: { heading: 'Spending' } },
  { path: 'debt', loadComponent: page, title: 'Debt - FinPath', data: { heading: 'Debt' } },
  { path: 'goals', loadComponent: page, title: 'Goals - FinPath', data: { heading: 'Goals' } },
  { path: 'more', loadComponent: page, title: 'More - FinPath', data: { heading: 'More' } },
  { path: '**', redirectTo: 'home' },
];
