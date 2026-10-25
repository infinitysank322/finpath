import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../expense/expense-api.service';
import { GoalInput, GoalStatusView, GoalView } from './goal.models';

/** HTTP edge for goals (T61). Returns API values untouched. */
@Injectable({ providedIn: 'root' })
export class GoalApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  list(): Observable<GoalView[]> {
    return this.http.get<GoalView[]>(`${this.base}/goals`);
  }
  create(body: GoalInput): Observable<GoalView> {
    return this.http.post<GoalView>(`${this.base}/goals`, body);
  }
  replace(id: string, body: GoalInput): Observable<GoalView> {
    return this.http.put<GoalView>(`${this.base}/goals/${encodeURIComponent(id)}`, body);
  }
  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/goals/${encodeURIComponent(id)}`);
  }
  status(id: string, plannedMonthlyPaise: number): Observable<GoalStatusView> {
    const params = new HttpParams().set('plannedMonthlyPaise', String(plannedMonthlyPaise));
    return this.http.get<GoalStatusView>(`${this.base}/goals/${encodeURIComponent(id)}/status`, { params });
  }
}
