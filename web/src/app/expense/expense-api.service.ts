import { HttpClient, HttpHeaders, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, timeout } from 'rxjs';
import { API_BASE } from '../api-base';
import { CategoryOption, ExpenseInput, ExpenseView, OUT_CATEGORIES } from './expense.models';

/** Re-exported so existing `from '../expense/expense-api.service'` imports keep working. */
export { API_BASE };
/** A POST that has not answered in this long fails with rxjs TimeoutError, which callers treat as retryable. */
export const CREATE_TIMEOUT_MS = 30_000;

/** HTTP edge for expenses. The offline queue (ExpenseQueueService) calls create() with its stored keys. */
@Injectable({ providedIn: 'root' })
export class ExpenseApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  categories(): CategoryOption[] {
    return OUT_CATEGORIES;
  }

  /** 201 = new row, 200 = replay of the same key; both are success. 409/400 surface as HttpErrorResponse. */
  create(input: ExpenseInput, idempotencyKey: string): Observable<HttpResponse<ExpenseView>> {
    return this.http
      .post<ExpenseView>(`${this.base}/expenses`, input, {
        headers: new HttpHeaders({ 'Idempotency-Key': idempotencyKey }),
        observe: 'response',
      })
      .pipe(timeout(CREATE_TIMEOUT_MS));
  }
}
