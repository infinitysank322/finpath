import { HttpBackend, HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { API_BASE } from '../api-base';
import { TokenResponse } from './session.store';

/**
 * T29 token endpoints. Uses HttpBackend directly, so no interceptor (and so no bearer header) ever touches
 * these calls; the interceptor also skips them by URL as a second line.
 */
@Injectable({ providedIn: 'root' })
export class AuthApiService {
  private readonly http = new HttpClient(inject(HttpBackend));
  private readonly base = inject(API_BASE);

  token(code: string, codeVerifier: string): Observable<TokenResponse> {
    return this.http.post<TokenResponse>(`${this.base}/auth/token`, { code, codeVerifier });
  }

  /** Revokes the refresh-token family. Always 204 (idempotent); callers ignore failures. */
  logout(refreshToken: string): Observable<void> {
    return this.http.post<void>(`${this.base}/auth/logout`, { refreshToken });
  }

  refresh(refreshToken: string): Observable<TokenResponse> {
    return this.http.post<TokenResponse>(`${this.base}/auth/refresh`, { refreshToken });
  }
}
