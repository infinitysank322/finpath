import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../expense/expense-api.service';
import { BudgetLine, BudgetStatus, EvaluateResult } from './budget.models';

/** HTTP edge for category budgets (T40). Returns API values untouched; cycleStart omitted means the current cycle. */
@Injectable({ providedIn: 'root' })
export class BudgetApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  status(cycleStart?: string): Observable<BudgetStatus> {
    return this.http.get<BudgetStatus>(`${this.base}/budgets/status`, { params: this.params(cycleStart) });
  }
  put(categoryCode: string, budgetPaise: number, cycleStart?: string): Observable<BudgetLine> {
    return this.http.put<BudgetLine>(`${this.base}/budgets/${encodeURIComponent(categoryCode)}`, { budgetPaise }, { params: this.params(cycleStart) });
  }
  delete(categoryCode: string, cycleStart?: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/budgets/${encodeURIComponent(categoryCode)}`, { params: this.params(cycleStart) });
  }
  evaluate(cycleStart?: string): Observable<EvaluateResult> {
    return this.http.post<EvaluateResult>(`${this.base}/budgets/evaluate`, null, { params: this.params(cycleStart) });
  }

  private params(cycleStart?: string): HttpParams {
    return cycleStart ? new HttpParams().set('cycleStart', cycleStart) : new HttpParams();
  }
}
