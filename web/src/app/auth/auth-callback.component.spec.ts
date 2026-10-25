import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, convertToParamMap, provideRouter } from '@angular/router';
import { API_BASE } from '../api-base';
import { ExpenseQueueService } from '../expense/expense-queue.service';
import { memoryStorage } from '../expense/queue-testing';
import { AuthCallbackComponent, CALLBACK_MESSAGES } from './auth-callback.component';
import { AuthService, BrowserNav, SIGNIN_KEY, SIGNIN_STORAGE } from './auth.service';
import { USER_A, tokenResponse } from './auth-testing';
import { AUTH_STORAGE, AuthSessionStore } from './session.store';

const BASE = 'http://localhost:8080';
const TOKEN_URL = `${BASE}/auth/token`;
const VERIFIER = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';

describe('AuthCallbackComponent', () => {
  let signin: ReturnType<typeof memoryStorage>;
  let ctrl: HttpTestingController;
  let navigate: jasmine.Spy;
  let replaceState: jasmine.Spy;
  let queue: { reload: jasmine.Spy; flush: jasmine.Spy };
  let assign: jasmine.Spy;

  const entry = (returnTo = '/spending/add', state = 'st-123') =>
    (signin.data[SIGNIN_KEY] = JSON.stringify({ verifier: VERIFIER, state, returnTo }));

  const render = (params: Record<string, string>) => {
    TestBed.configureTestingModule({
      imports: [AuthCallbackComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_BASE, useValue: BASE },
        { provide: SIGNIN_STORAGE, useValue: signin },
        { provide: AUTH_STORAGE, useValue: memoryStorage() },
        { provide: ExpenseQueueService, useValue: queue },
        { provide: BrowserNav, useValue: { origin: () => 'http://localhost:4200', assign } },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap(params) } } },
      ],
    });
    ctrl = TestBed.inject(HttpTestingController);
    navigate = spyOn(TestBed.inject(Router), 'navigateByUrl').and.resolveTo(true);
    replaceState = spyOn(TestBed.inject(Location), 'replaceState');
    const f = TestBed.createComponent(AuthCallbackComponent);
    f.detectChanges();
    return f;
  };

  beforeEach(() => {
    signin = memoryStorage();
    queue = { reload: jasmine.createSpy('reload'), flush: jasmine.createSpy('flush').and.resolveTo() };
    assign = jasmine.createSpy('assign');
  });
  afterEach(() => ctrl.verify());

  it('happy path: strips the code from the URL first, sends exactly {code, codeVerifier}, stores the session, flushes the queue, replaces history', () => {
    entry('/spending/add');
    render({ code: 'one-time-code', state: 'st-123' });

    expect(signin.data[SIGNIN_KEY]).toBeUndefined(); // removed before anything else
    const req = ctrl.expectOne(TOKEN_URL);
    expect(replaceState).toHaveBeenCalledOnceWith('/auth/callback'); // already gone while the request is in flight
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ code: 'one-time-code', codeVerifier: VERIFIER });
    expect(Object.keys(req.request.body)).toEqual(['code', 'codeVerifier']);
    expect(req.request.headers.has('Authorization')).toBeFalse();
    expect(req.request.withCredentials).toBeFalse();

    req.flush(tokenResponse(USER_A, 'refresh-1'));
    const store = TestBed.inject(AuthSessionStore);
    expect(store.isSignedIn()).toBeTrue();
    expect(store.userId()).toBe(USER_A);
    expect(queue.reload).toHaveBeenCalled();
    expect(queue.flush).toHaveBeenCalledTimes(1);
    expect(navigate).toHaveBeenCalledOnceWith('/spending/add', { replaceUrl: true });
  });

  it('state mismatch: no /auth/token call, entry removed, friendly error with Try again', () => {
    entry('/home', 'st-123');
    const f = render({ code: 'c', state: 'someone-elses-state' });
    ctrl.expectNone(TOKEN_URL);
    expect(signin.data[SIGNIN_KEY]).toBeUndefined();
    const root: HTMLElement = f.nativeElement;
    expect(root.querySelector('[role=alert]')!.textContent).toContain(CALLBACK_MESSAGES.expired);
    expect(root.querySelector('button')!.textContent!.trim()).toBe('Try again');
    expect(TestBed.inject(AuthSessionStore).isSignedIn()).toBeFalse();
    expect(navigate).not.toHaveBeenCalled();
  });

  it('missing state, missing entry, or missing code: no /auth/token call', () => {
    entry();
    render({ code: 'c' });
    ctrl.expectNone(TOKEN_URL);
    expect(signin.data[SIGNIN_KEY]).toBeUndefined();
    TestBed.resetTestingModule();

    render({ code: 'c', state: 'st-123' }); // no entry (e.g. a reload, or a different tab)
    ctrl.expectNone(TOKEN_URL);
    TestBed.resetTestingModule();

    entry();
    render({ state: 'st-123' });
    ctrl.expectNone(TOKEN_URL);
    expect(TestBed.inject(AuthSessionStore).isSignedIn()).toBeFalse();
    expect(navigate).not.toHaveBeenCalled();
  });

  for (const [error, key] of [
    ['email_not_verified', 'email_not_verified'],
    ['login_failed', 'login_failed'],
    ['anything_else', 'login_failed'],
  ] as const) {
    it(`?error=${error}: no /auth/token call, shows the fixed message`, () => {
      entry();
      const f = render({ error, state: 'st-123' });
      ctrl.expectNone(TOKEN_URL);
      expect(signin.data[SIGNIN_KEY]).toBeUndefined();
      expect(replaceState).toHaveBeenCalledWith('/auth/callback');
      expect(f.nativeElement.querySelector('[role=alert]').textContent).toContain(CALLBACK_MESSAGES[key]);
    });
  }

  it('invalid_grant from /auth/token shows an error and stores nothing', () => {
    entry();
    const f = render({ code: 'used', state: 'st-123' });
    ctrl.expectOne(TOKEN_URL).flush({ status: 401, error: 'invalid_grant' }, { status: 401, statusText: 'Unauthorized' });
    f.detectChanges();
    expect(f.nativeElement.querySelector('[role=alert]').textContent).toContain(CALLBACK_MESSAGES.invalid_grant);
    expect(TestBed.inject(AuthSessionStore).isSignedIn()).toBeFalse();
    expect(queue.flush).not.toHaveBeenCalled();
  });

  it('open-redirect: a stored returnTo that is not an in-app path lands on /home', () => {
    entry('//evil.example/phish');
    render({ code: 'c', state: 'st-123' });
    ctrl.expectOne(TOKEN_URL).flush(tokenResponse(USER_A, 'r'));
    expect(navigate).toHaveBeenCalledOnceWith('/home', { replaceUrl: true });
  });

  it('open-redirect: an auth path as returnTo lands on /home', () => {
    entry('/auth/callback?code=x');
    render({ code: 'c', state: 'st-123' });
    ctrl.expectOne(TOKEN_URL).flush(tokenResponse(USER_A, 'r'));
    expect(navigate).toHaveBeenCalledOnceWith('/home', { replaceUrl: true });
  });

  it('Try again starts a fresh round trip with the same return path', async () => {
    entry('/more', 'st-123');
    const f = render({ code: 'c', state: 'wrong' });
    ctrl.expectNone(TOKEN_URL);
    const start = spyOn(TestBed.inject(AuthService), 'startSignIn').and.resolveTo(true);
    (f.nativeElement.querySelector('button') as HTMLButtonElement).click();
    await f.whenStable();
    expect(start).toHaveBeenCalledOnceWith('/more');
  });
});
