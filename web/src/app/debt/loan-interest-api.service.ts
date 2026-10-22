import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../expense/expense-api.service';
import { LoanDetailView } from './loan-interest.models';

/** HTTP edge for the loan detail (FR-13). Returns API values untouched. */
@Injectable({ providedIn: 'root' })
export class LoanInterestApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  detail(id: string): Observable<LoanDetailView> {
    return this.http.get<LoanDetailView>(`${this.base}/loans/${encodeURIComponent(id)}/detail`);
  }
}
