import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router, provideRouter } from '@angular/router';
import { routes } from '../app.routes';
import { memoryStorage } from '../expense/queue-testing';
import { QUEUE_STORAGE } from '../expense/expense-queue.service';
import { AuthService, SIGNIN_STORAGE } from './auth.service';
import { USER_A, tokenResponse } from './auth-testing';
import { AUTH_STORAGE, AuthSessionStore } from './session.store';

describe('auth guard (real app routes)', () => {
  let router: Router;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideRouter(routes),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: AUTH_STORAGE, useValue: memoryStorage() },
        { provide: SIGNIN_STORAGE, useValue: memoryStorage() },
        { provide: QUEUE_STORAGE, useValue: memoryStorage() },
      ],
    });
    router = TestBed.inject(Router);
  });

  it('signed out: a deep link goes to /sign-in and the attempted URL is remembered (not in the query)', async () => {
    await router.navigateByUrl('/spending/add');
    expect(router.url).toBe('/sign-in');
    expect(TestBed.inject(AuthService).pendingReturnTo()).toBe('/spending/add');
  });

  it('signed out: every app route is guarded, including the ** catch-all and the empty path', async () => {
    for (const url of ['/home', '/more', '/debt/loans/1/prepay', '/no-such-page', '/']) {
      await router.navigateByUrl(url);
      expect(router.url).withContext(url).toBe('/sign-in');
    }
  });

  it('signed out: /sign-in and /auth/callback are reachable', async () => {
    await router.navigateByUrl('/sign-in');
    expect(router.url).toBe('/sign-in');
    await router.navigateByUrl('/auth/callback?state=s');
    expect(router.url).toBe('/auth/callback?state=s');
  });

  it('signed in: app routes open, /sign-in goes to /home, ** still redirects to /home', async () => {
    TestBed.inject(AuthSessionStore).save(tokenResponse(USER_A, 'r'));
    await router.navigateByUrl('/more');
    expect(router.url).toBe('/more');
    await router.navigateByUrl('/sign-in');
    expect(router.url).toBe('/home');
    await router.navigateByUrl('/no-such-page');
    expect(router.url).toBe('/home');
  });

  it('an expired access token still counts as signed in (it is refreshed on use, and works offline)', async () => {
    TestBed.inject(AuthSessionStore).save(tokenResponse(USER_A, 'r', 1), 0);
    await router.navigateByUrl('/home');
    expect(router.url).toBe('/home');
  });
});
