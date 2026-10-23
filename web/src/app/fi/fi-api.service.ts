import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../expense/expense-api.service';
import { FiPlanRequest, FiPlanView } from './fi.models';

/** HTTP edge for the FI planner (FR-21). Sends inputs, returns API values untouched. */
@Injectable({ providedIn: 'root' })
export class FiApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  plan(req: FiPlanRequest): Observable<FiPlanView> {
    let params = new HttpParams()
      .set('todayMonthlyExpensePaise', req.todayMonthlyExpensePaise)
      .set('yearsToFi', req.yearsToFi);
    if (req.todayMonthlyPassivePaise !== undefined) params = params.set('todayMonthlyPassivePaise', req.todayMonthlyPassivePaise);
    if (req.openingBalancePaise !== undefined) params = params.set('openingBalancePaise', req.openingBalancePaise);
    if (req.monthlySipPaise !== undefined) params = params.set('monthlySipPaise', req.monthlySipPaise);
    if (req.stepUp !== undefined) params = params.set('stepUp', req.stepUp);
    return this.http.get<FiPlanView>(`${this.base}/fi-planner`, { params });
  }
}
