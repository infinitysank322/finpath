import { HttpErrorResponse, HttpEvent, HttpHandlerFn, HttpInterceptorFn, HttpRequest } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable, catchError, defer, from, of, switchMap, throwError } from 'rxjs';
import { API_BASE } from '../api-base';
import { AuthService } from './auth.service';
import { AuthSessionStore, Session, isExpired } from './session.store';

const UNAUTHENTICATED_PATHS = ['/auth/token', '/auth/refresh'];

/**
 * API path of `url` if it is a FinPath API call, else null.
 * apiBase '' -> only relative URLs starting with a single '/'. Otherwise the URL must start with apiBase + '/',
 * so a look-alike host (http://localhost:8080.evil) never matches.
 */
export function apiPathOf(url: string, apiBase: string): string | null {
  if (apiBase === '') return url.startsWith('/') && !url.startsWith('//') && !url.startsWith('/\\') ? url : null;
  return url.startsWith(apiBase + '/') ? url.slice(apiBase.length) : null;
}

export function needsBearer(url: string, apiBase: string): boolean {
  const path = apiPathOf(url, apiBase);
  if (path === null) return false;
  const bare = path.split(/[?#]/)[0];
  return !UNAUTHENTICATED_PATHS.includes(bare);
}

function withBearer(req: HttpRequest<unknown>, s: Session | null): HttpRequest<unknown> {
  return s ? req.clone({ setHeaders: { Authorization: `Bearer ${s.accessToken}` } }) : req;
}

/**
 * Attaches the bearer token to FinPath API calls, refreshing first when it has expired. On a 401 it joins one
 * shared refresh and retries the request once. If the refresh fails, the original error is passed on, so the
 * offline queue still sees a retryable 401 (or network error) and keeps the item.
 */
export const authInterceptor: HttpInterceptorFn = (req: HttpRequest<unknown>, next: HttpHandlerFn): Observable<HttpEvent<unknown>> => {
  if (!needsBearer(req.url, inject(API_BASE))) return next(req);
  const store = inject(AuthSessionStore);
  const auth = inject(AuthService);

  const start = store.session();
  const ready$: Observable<Session | null> =
    start && isExpired(start) ? defer(() => auth.refreshAfter(start)) : of(start);

  return ready$.pipe(
    switchMap((sent) =>
      next(withBearer(req, sent)).pipe(
        catchError((err: unknown) => {
          if (!(err instanceof HttpErrorResponse) || err.status !== 401 || !sent) return throwError(() => err);
          return from(auth.refreshAfter(sent)).pipe(
            // Refresh rejected (401), no session, or another user now signed in: surface the original 401.
            // Offline/5xx on the refresh: surface that instead.
            catchError((e: unknown) =>
              throwError(() => (e instanceof HttpErrorResponse && e.status !== 401 ? e : err)),
            ),
            switchMap((s) =>
              // Belt and braces: a request made for one user is never retried with another user's bearer.
              s.userId === sent.userId ? next(withBearer(req, s)) : throwError(() => err),
            ), // retried once: this inner call is not caught again
          );
        }),
      ),
    ),
  );
};
