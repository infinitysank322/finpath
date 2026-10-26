import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../expense/expense-api.service';
import { CategoryBreakdown, SpendingComparison, TagSummary } from './spending.models';

/** HTTP edge for the spending screens. Returns API values untouched; no cycleStart means the current cycle. */
@Injectable({ providedIn: 'root' })
export class SpendingApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  tagSummary(): Observable<TagSummary> {
    return this.http.get<TagSummary>(`${this.base}/expenses/tag-summary`);
  }
  categories(cycleStart?: string): Observable<CategoryBreakdown> {
    return this.http.get<CategoryBreakdown>(`${this.base}/spending/categories`, { params: this.params(cycleStart) });
  }
  comparison(cycleStart?: string): Observable<SpendingComparison> {
    return this.http.get<SpendingComparison>(`${this.base}/spending/comparison`, { params: this.params(cycleStart) });
  }

  private params(cycleStart?: string): HttpParams {
    return cycleStart ? new HttpParams().set('cycleStart', cycleStart) : new HttpParams();
  }
}
