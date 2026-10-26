import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../expense/expense-api.service';
import { HoldingAggregateView, HoldingInput, HoldingView } from './holding.models';

/** HTTP edge for holdings (T65). Returns API values untouched. */
@Injectable({ providedIn: 'root' })
export class HoldingApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  list(): Observable<HoldingView[]> {
    return this.http.get<HoldingView[]>(`${this.base}/holdings`);
  }
  aggregate(): Observable<HoldingAggregateView> {
    return this.http.get<HoldingAggregateView>(`${this.base}/holdings/aggregate`);
  }
  create(body: HoldingInput): Observable<HoldingView> {
    return this.http.post<HoldingView>(`${this.base}/holdings`, body);
  }
  replace(id: string, body: HoldingInput): Observable<HoldingView> {
    return this.http.put<HoldingView>(`${this.base}/holdings/${encodeURIComponent(id)}`, body);
  }
  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/holdings/${encodeURIComponent(id)}`);
  }
}
