import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../expense/expense-api.service';
import { GuardView } from './guard.models';

/** HTTP edge for the debt-trap guard (FR-18). Returns API values untouched. */
@Injectable({ providedIn: 'root' })
export class GuardApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  current(): Observable<GuardView> {
    return this.http.get<GuardView>(`${this.base}/loans/debt-trap-guard`);
  }
}
