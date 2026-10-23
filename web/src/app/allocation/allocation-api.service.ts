import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../expense/expense-api.service';
import { AllocationView, RebalancePreview } from './allocation.models';

/** HTTP edge for the cycle allocation (FR-22). Returns API values untouched. */
@Injectable({ providedIn: 'root' })
export class AllocationApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  current(): Observable<AllocationView> {
    return this.http.get<AllocationView>(`${this.base}/allocation`);
  }

  run(): Observable<AllocationView> {
    return this.http.post<AllocationView>(`${this.base}/allocation/run`, null);
  }

  rebalance(amountPaise: number): Observable<RebalancePreview> {
    return this.http.post<RebalancePreview>(`${this.base}/plan/rebalance`, { amountPaise });
  }
}
