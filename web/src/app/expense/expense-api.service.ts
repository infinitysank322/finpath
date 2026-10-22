import { HttpClient, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, InjectionToken, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { CategoryOption, ExpenseInput, ExpenseView, OUT_CATEGORIES } from './expense.models';

export const API_BASE = new InjectionToken<string>('API_BASE', { providedIn: 'root', factory: () => '' });

/** HTTP edge for expenses. T43 will wrap/replace create() with the offline queue. */
@Injectable({ providedIn: 'root' })
export class ExpenseApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  categories(): CategoryOption[] {
    return OUT_CATEGORIES;
  }

  /** 201 = new row, 200 = replay of the same key; both are success. 409/400 surface as HttpErrorResponse. */
  create(input: ExpenseInput, idempotencyKey: string): Observable<HttpResponse<ExpenseView>> {
    return this.http.post<ExpenseView>(`${this.base}/expenses`, input, {
      headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }),
      observe: 'response',
    });
  }
}
