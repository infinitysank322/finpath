import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../expense/expense-api.service';
import { SinkingFundView } from './sinking-fund.models';

/** HTTP edge for the bullet-loan sinking fund (FR-16). Returns API values untouched. */
@Injectable({ providedIn: 'root' })
export class SinkingFundApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  status(id: string, savedPaise: number): Observable<SinkingFundView> {
    const params = new HttpParams().set('savedPaise', savedPaise);
    return this.http.get<SinkingFundView>(`${this.base}/loans/${encodeURIComponent(id)}/sinking-fund`, { params });
  }
}
