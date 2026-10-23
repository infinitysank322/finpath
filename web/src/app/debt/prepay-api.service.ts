import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../expense/expense-api.service';
import { PrepayQuery, PrepaymentView } from './prepay.models';

/** HTTP edge for the prepayment simulator (FR-17). Returns API values untouched. */
@Injectable({ providedIn: 'root' })
export class PrepayApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  simulate(id: string, q: PrepayQuery): Observable<PrepaymentView> {
    let params = new HttpParams().set('mode', q.mode);
    if (q.lumpPaise !== undefined) params = params.set('lumpPaise', q.lumpPaise);
    if (q.afterInstalment !== undefined) params = params.set('afterInstalment', q.afterInstalment);
    if (q.newEmiPaise !== undefined) params = params.set('newEmiPaise', q.newEmiPaise);
    if (q.closeMonths !== undefined) params = params.set('closeMonths', q.closeMonths);
    return this.http.get<PrepaymentView>(`${this.base}/loans/${encodeURIComponent(id)}/prepayment`, { params });
  }
}
