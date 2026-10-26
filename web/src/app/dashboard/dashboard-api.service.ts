import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../api-base';
import { DashboardView } from './dashboard.models';

/** HTTP edge for the single home-screen aggregate (T74). Returns API values untouched. */
@Injectable({ providedIn: 'root' })
export class DashboardApiService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE);

  get(): Observable<DashboardView> {
    return this.http.get<DashboardView>(`${this.base}/dashboard`);
  }
}
