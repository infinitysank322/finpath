import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../expense/expense-api.service';
import { PayoffPlanView, PayoffStrategy, RollOffView } from './payoff.models';

/** HTTP edge for the payoff plan (FR-14) and EMI roll-off (FR-15). Returns API values untouched. */
@Injectable({ providedIn: 'root' })
export class PayoffApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  payoffPlan(strategy: PayoffStrategy): Observable<PayoffPlanView> {
    return this.http.get<PayoffPlanView>(`${this.base}/loans/payoff-plan`, { params: { strategy } });
  }
  rollOff(): Observable<RollOffView> {
    return this.http.get<RollOffView>(`${this.base}/loans/roll-off`);
  }
}
