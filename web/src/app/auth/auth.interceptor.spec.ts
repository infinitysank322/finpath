import { HttpClient, HttpErrorResponse, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed, fakeAsync, flushMicrotasks } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { API_BASE } from '../api-base';
import { ExpenseQueueService, QUEUE_STORAGE, queueKeyFor } from '../expense/expense-queue.service';
import { ExpenseInput } from '../expense/expense.models';
import { memoryStorage } from '../expense/queue-testing';
import { REFRESH_LOCK, SIGNIN_STORAGE } from './auth.service';
import { USER_A, USER_B, fakeJwt, tokenResponse } from './auth-testing';
import { authInterceptor, needsBearer } from './auth.interceptor';
import { AUTH_STORAGE, AuthSessionStore, SESSION_KEY, Session } from './session.store';

const BASE = 'http://localhost:8080';

describe('needsBearer', () => {
  it('with an absolute apiBase: only URLs under apiBase + "/", never the token endpoints', () => {
    expect(needsBearer(`${BASE}/expenses`, BASE)).toBeTrue();
    expect(needsBearer(`${BASE}/plan/current?x=1`, BASE)).toBeTrue();
    expect(needsBearer(`${BASE}/auth/token`, BASE)).toBeFalse();
    expect(needsBearer(`${BASE}/auth/refresh`, BASE)).toBeFalse();
    expect(needsBearer(`${BASE}/auth/refresh?x=1`, BASE)).toBeFalse();
    expect(needsBearer('http://localhost:8080.evil.example/expenses', BASE)).toBeFalse();
    expect(needsBearer('http://localhost:80801/expenses', BASE)).toBeFalse();
    expect(needsBearer('https://accounts.google.com/x', BASE)).toBeFalse();
    expect(needsBearer('/expenses', BASE)).toBeFalse(); // relative = the web origin, not the API
  });

  it("with apiBase '': only relative URLs starting with a single '/'", () => {
    expect(needsBearer('/expenses', '')).toBeTrue();
    expect(needsBearer('//evil.example/expenses', '')).toBeFalse();
    expect(needsBearer('/\\evil.example/expenses', '')).toBeFalse();
    expect(needsBearer('https://evil.example/expenses', '')).toBeFalse();
    expect(needsBearer('/auth/token', '')).toBeFalse();
    expect(needsBearer('/auth/refresh', '')).toBeFalse();
  });
});

describe('authInterceptor', () => {
  let storage: ReturnType<typeof memoryStorage>;
  let http: HttpClient;
  let ctrl: HttpTestingController;
  let store: AuthSessionStore;
  let navigate: jasmine.Spy;

  const signIn = (refreshToken = 'r1', expiresIn = 900, tag = 'a1') => store.save(tokenResponse(USER_A, refreshToken, expiresIn, tag));
  const bearerOf = (s: Session) => `Bearer ${s.accessToken}`;
  const unauthorized = { status: 401, statusText: 'Unauthorized' };

  beforeEach(() => {
    storage = memoryStorage();
    TestBed.configureTestingModule({
      providers: [
        provideRouter([]),
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
        { provide: API_BASE, useValue: BASE },
        { provide: AUTH_STORAGE, useValue: storage },
        { provide: SIGNIN_STORAGE, useValue: memoryStorage() },
        { provide: REFRESH_LOCK, useValue: <T>(fn: () => Promise<T>) => fn() },
        { provide: QUEUE_STORAGE, useValue: storage },
      ],
    });
    http = TestBed.inject(HttpClient);
    ctrl = TestBed.inject(HttpTestingController);
    store = TestBed.inject(AuthSessionStore);
    navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);
  });
  afterEach(() => ctrl.verify());

  it('attaches the bearer to API calls only, never to /auth/* or another host', () => {
    const s = signIn();
    http.get(`${BASE}/expenses`).subscribe();
    http.post(`${BASE}/auth/token`, {}).subscribe();
    http.post(`${BASE}/auth/refresh`, {}).subscribe();
    http.get('https://cdn.example/x.json').subscribe();
    http.get('http://localhost:8080.evil.example/expenses').subscribe();

    expect(ctrl.expectOne(`${BASE}/expenses`).request.headers.get('Authorization')).toBe(bearerOf(s));
    for (const url of [`${BASE}/auth/token`, `${BASE}/auth/refresh`, 'https://cdn.example/x.json', 'http://localhost:8080.evil.example/expenses']) {
      const r = ctrl.expectOne(url);
      expect(r.request.headers.has('Authorization')).withContext(url).toBeFalse();
      r.flush({});
    }
  });

  it('signed out: sends without a bearer, passes a 401 through, no refresh and no navigation', () => {
    let status = -1;
    http.get(`${BASE}/expenses`).subscribe({ error: (e: HttpErrorResponse) => (status = e.status) });
    const r = ctrl.expectOne(`${BASE}/expenses`);
    expect(r.request.headers.has('Authorization')).toBeFalse();
    r.flush({ error: 'unauthorized' }, unauthorized);
    expect(status).toBe(401);
    ctrl.expectNone(`${BASE}/auth/refresh`);
    expect(navigate).not.toHaveBeenCalled();
  });

  it('401 -> one refresh -> retries once with the rotated token', fakeAsync(() => {
    signIn('r1');
    let body: unknown;
    http.get(`${BASE}/expenses`).subscribe((b) => (body = b));
    ctrl.expectOne(`${BASE}/expenses`).flush({ error: 'invalid_token' }, unauthorized);

    const refresh = ctrl.expectOne(`${BASE}/auth/refresh`);
    expect(refresh.request.body).toEqual({ refreshToken: 'r1' });
    expect(refresh.request.headers.has('Authorization')).toBeFalse();
    refresh.flush(tokenResponse(USER_A, 'r2', 900, 'a2'));
    flushMicrotasks();

    const retry = ctrl.expectOne(`${BASE}/expenses`);
    expect(retry.request.headers.get('Authorization')).toBe(bearerOf(store.session()!));
    expect(store.session()!.refreshToken).toBe('r2');
    retry.flush({ ok: true });
    expect(body).toEqual({ ok: true });
  }));

  it('concurrent 401s share a single refresh and each retries once', fakeAsync(() => {
    signIn('r1');
    const done: string[] = [];
    http.get(`${BASE}/a`).subscribe(() => done.push('a'));
    http.get(`${BASE}/b`).subscribe(() => done.push('b'));
    ctrl.expectOne(`${BASE}/a`).flush(null, unauthorized);
    ctrl.expectOne(`${BASE}/b`).flush(null, unauthorized);
    ctrl.expectOne(`${BASE}/auth/refresh`).flush(tokenResponse(USER_A, 'r2', 900, 'a2'));
    flushMicrotasks();
    const a = ctrl.expectOne(`${BASE}/a`);
    const b = ctrl.expectOne(`${BASE}/b`);
    expect(a.request.headers.get('Authorization')).toBe(bearerOf(store.session()!));
    a.flush({});
    b.flush({});
    expect(done.sort()).toEqual(['a', 'b']);
  }));

  it('a second 401 after the retry is passed on, not refreshed again', fakeAsync(() => {
    signIn('r1');
    let status = -1;
    http.get(`${BASE}/x`).subscribe({ error: (e: HttpErrorResponse) => (status = e.status) });
    ctrl.expectOne(`${BASE}/x`).flush(null, unauthorized);
    ctrl.expectOne(`${BASE}/auth/refresh`).flush(tokenResponse(USER_A, 'r2'));
    flushMicrotasks();
    ctrl.expectOne(`${BASE}/x`).flush(null, unauthorized);
    flushMicrotasks();
    ctrl.expectNone(`${BASE}/auth/refresh`);
    expect(status).toBe(401);
  }));

  it('refresh rejected (401) -> session cleared, routed to /sign-in, original 401 propagated', fakeAsync(() => {
    signIn('r1');
    let err: HttpErrorResponse | undefined;
    http.get(`${BASE}/x`).subscribe({ error: (e) => (err = e) });
    ctrl.expectOne(`${BASE}/x`).flush(null, unauthorized);
    ctrl.expectOne(`${BASE}/auth/refresh`).flush({ status: 401, error: 'invalid_grant' }, unauthorized);
    flushMicrotasks();
    expect(store.isSignedIn()).toBeFalse();
    expect(storage.data[SESSION_KEY]).toBeUndefined();
    expect(navigate).toHaveBeenCalledWith('/sign-in');
    expect(err?.status).toBe(401);
    expect(err?.url).toBe(`${BASE}/x`);
  }));

  for (const [label, fail] of [
    ['a network error', (r: ReturnType<HttpTestingController['expectOne']>) => r.error(new ProgressEvent('error'))],
    ['a 503', (r: ReturnType<HttpTestingController['expectOne']>) => r.flush(null, { status: 503, statusText: 'Down' })],
  ] as const) {
    it(`refresh failing with ${label} keeps the session (offline never signs out) and passes that error on`, fakeAsync(() => {
      const s = signIn('r1');
      let err: HttpErrorResponse | undefined;
      http.get(`${BASE}/x`).subscribe({ error: (e) => (err = e) });
      ctrl.expectOne(`${BASE}/x`).flush(null, unauthorized);
      fail(ctrl.expectOne(`${BASE}/auth/refresh`));
      flushMicrotasks();
      expect(store.session()).toEqual(s);
      expect(JSON.parse(storage.data[SESSION_KEY])).toEqual(s);
      expect(navigate).not.toHaveBeenCalled();
      expect(err?.status).not.toBe(401);
    }));
  }

  it('an expired access token is refreshed before the call, so the stale token is never sent', fakeAsync(() => {
    signIn('r1', 10); // 10 s left: inside the 30 s skew
    http.get(`${BASE}/x`).subscribe();
    ctrl.expectNone(`${BASE}/x`);
    ctrl.expectOne(`${BASE}/auth/refresh`).flush(tokenResponse(USER_A, 'r2', 900, 'a2'));
    flushMicrotasks();
    const r = ctrl.expectOne(`${BASE}/x`);
    expect(r.request.headers.get('Authorization')).toBe(bearerOf(store.session()!));
    expect(store.session()!.refreshToken).toBe('r2');
    r.flush({});
  }));

  it('uses a session another tab already rotated instead of replaying the stale refresh token', fakeAsync(() => {
    signIn('r1');
    http.get(`${BASE}/x`).subscribe();
    const first = ctrl.expectOne(`${BASE}/x`);
    // Meanwhile another tab refreshed and wrote the rotated session (no storage event delivered yet).
    const otherTab = { accessToken: 'other-tab-access', expiresAt: Date.now() + 900_000, refreshToken: 'r2', userId: USER_A };
    storage.data[SESSION_KEY] = JSON.stringify(otherTab);
    first.flush(null, unauthorized);
    flushMicrotasks();
    ctrl.expectNone(`${BASE}/auth/refresh`);
    expect(ctrl.expectOne(`${BASE}/x`).request.headers.get('Authorization')).toBe('Bearer other-tab-access');
  }));

  it('never retries a request with a different user session (user B signed in in another tab)', fakeAsync(() => {
    signIn('r1'); // user A
    let err: HttpErrorResponse | undefined;
    http.get(`${BASE}/x`).subscribe({ error: (e) => (err = e) });
    const first = ctrl.expectOne(`${BASE}/x`);
    const userB = { accessToken: fakeJwt(USER_B), expiresAt: Date.now() + 900_000, refreshToken: 'rB', userId: USER_B };
    storage.data[SESSION_KEY] = JSON.stringify(userB);
    first.flush(null, unauthorized);
    flushMicrotasks();
    ctrl.expectNone(`${BASE}/auth/refresh`); // B's refresh token is not used either
    ctrl.expectNone(`${BASE}/x`); // no retry at all, so never sent with B's bearer
    expect(err?.status).toBe(401);
    expect(JSON.parse(storage.data[SESSION_KEY])).toEqual(userB); // B's session untouched
    expect(navigate).not.toHaveBeenCalled();
  }));

  it('an expired user-A token is not swapped for a stored user-B session before sending', fakeAsync(() => {
    signIn('r1', 10); // A, inside the skew
    storage.data[SESSION_KEY] = JSON.stringify({ accessToken: fakeJwt(USER_B), expiresAt: Date.now() + 900_000, refreshToken: 'rB', userId: USER_B });
    // the store still holds A in memory until the next reload, which the refresh path does first
    let err: unknown;
    http.get(`${BASE}/x`).subscribe({ error: (e) => (err = e) });
    flushMicrotasks();
    ctrl.expectNone(`${BASE}/auth/refresh`);
    ctrl.expectNone(`${BASE}/x`);
    expect(err).toBeDefined();
  }));

  it('the offline queue still treats 401 as retryable: a failed refresh keeps the queued expense', fakeAsync(() => {
    signIn('r1');
    const queue = TestBed.inject(ExpenseQueueService);
    const input: ExpenseInput = { txnDate: '2026-10-22', amountPaise: 500, categoryCode: 'DINING', mode: 'UPI', tag: null, note: null };
    queue.enqueue(input, 'key-00000001');
    void queue.flush();
    ctrl.expectOne(`${BASE}/expenses`).flush(null, unauthorized);
    ctrl.expectOne(`${BASE}/auth/refresh`).flush(null, unauthorized);
    flushMicrotasks();
    expect(store.isSignedIn()).toBeFalse();
    expect(queue.items().map((i) => i.status)).toEqual(['pending']); // not marked terminal
    expect(JSON.parse(storage.data[queueKeyFor(USER_A)]).map((i: { key: string }) => i.key)).toEqual(['key-00000001']);
    queue.ngOnDestroy(); // clear the backoff timer
  }));
});
