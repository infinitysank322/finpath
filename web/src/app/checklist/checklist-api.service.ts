import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../api-base';
import { ChecklistItem, ChecklistView, StreakView } from './checklist.models';

/** HTTP edge for the monthly checklist (T71). Returns API values untouched. */
@Injectable({ providedIn: 'root' })
export class ChecklistApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  current(): Observable<ChecklistView> {
    return this.http.get<ChecklistView>(`${this.base}/checklist`);
  }
  generate(): Observable<ChecklistView> {
    return this.http.post<ChecklistView>(`${this.base}/checklist/generate`, {});
  }
  markDone(id: string): Observable<ChecklistItem> {
    return this.http.post<ChecklistItem>(`${this.base}/checklist/items/${encodeURIComponent(id)}/done`, {});
  }
  streak(): Observable<StreakView> {
    return this.http.get<StreakView>(`${this.base}/checklist/streak`);
  }
}
