import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, forkJoin, map, of } from 'rxjs';
import { GuardApiService } from './guard-api.service';
import { API_BASE } from '../api-base';
import { LoanDetailView, LoanInput, LoanView } from './loan.models';

/** HTTP edge for loans (FR-12/13). Returns API values untouched. */
@Injectable({ providedIn: 'root' })
export class LoanApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  list(): Observable<LoanView[]> {
    return this.http.get<LoanView[]>(`${this.base}/loans`);
  }

  /** A 409 debt_trap_blocked is re-sent with confirmBlock=true only after the user confirms in the dialog. */
  create(input: LoanInput, confirmBlock = false): Observable<LoanView> {
    let params = new HttpParams();
    if (confirmBlock) params = params.set('confirmBlock', 'true');
    return this.http.post<LoanView>(`${this.base}/loans`, input, { params });
  }

  detail(id: string): Observable<LoanDetailView> {
    return this.http.get<LoanDetailView>(`${this.base}/loans/${encodeURIComponent(id)}/detail`);
  }

  remove(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/loans/${encodeURIComponent(id)}`);
  }
}

/** Loans and the server's total EMI as one request: a failed guard call yields a null total, never a stale one. */
export function loansWithTotal(loans: LoanApiService, guard: GuardApiService): Observable<{ rows: LoanView[]; totalEmiPaise: number | null }> {
  return forkJoin({
    rows: loans.list(),
    totalEmiPaise: guard.current().pipe(map((g) => g.totalEmiPaise), catchError(() => of(null))),
  });
}
