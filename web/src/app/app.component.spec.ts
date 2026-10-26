import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { Router, provideRouter } from '@angular/router';
import { AppComponent } from './app.component';
import { AUTH_STORAGE, AuthSessionStore, SESSION_KEY } from './auth/session.store';
import { AuthService, SIGNIN_STORAGE } from './auth/auth.service';
import { USER_A, tokenResponse } from './auth/auth-testing';
import { QUEUE_STORAGE } from './expense/expense-queue.service';
import { memoryStorage } from './expense/queue-testing';
import { routes } from './app.routes';
import { serviceWorkerOptions } from './app.config';
import { THEME_KEY } from './theme.service';

describe('AppComponent shell', () => {
  let authStorage: ReturnType<typeof memoryStorage>;
  beforeEach(async () => {
    authStorage = memoryStorage();
    localStorage.removeItem(THEME_KEY);
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting(), { provide: AUTH_STORAGE, useValue: authStorage },
        { provide: SIGNIN_STORAGE, useValue: memoryStorage() },
        { provide: QUEUE_STORAGE, useValue: memoryStorage() },
      ],
    }).compileComponents();
  });

  it('renders a labelled bottom nav with four destinations and a central Add expense button', () => {
    const f = TestBed.createComponent(AppComponent);
    f.detectChanges();
    const nav = f.nativeElement.querySelector('nav[aria-label="Primary"]') as HTMLElement;
    const labels = Array.from(nav.querySelectorAll('a')).map((a) => a.textContent?.trim());
    expect(labels).toEqual(['Home', 'Spend', '', 'Debt', 'Goals']);
    expect(nav.querySelector('a.add')?.getAttribute('aria-label')).toBe('Add expense');
  });

  it('hides the bottom nav and More link on /sign-in', async () => {
    const f = TestBed.createComponent(AppComponent);
    await TestBed.inject(Router).navigateByUrl('/sign-in');
    f.detectChanges();
    const root: HTMLElement = f.nativeElement;
    expect(root.querySelector('nav[aria-label="Primary"]')).toBeNull();
    expect(root.querySelector('a[aria-label="More"]')).toBeNull();
  });

  it('session lost while signed in (another tab signed out): goes to /sign-in, remembering where the user was', async () => {
    const f = TestBed.createComponent(AppComponent);
    const router = TestBed.inject(Router);
    TestBed.inject(AuthSessionStore).save(tokenResponse(USER_A, 'r'));
    f.detectChanges();
    await router.navigateByUrl('/more/income');
    f.detectChanges();
    expect(router.url).toBe('/more/income');

    delete authStorage.data[SESSION_KEY];
    window.dispatchEvent(new StorageEvent('storage', { key: SESSION_KEY }));
    f.detectChanges(); // runs the effect
    await f.whenStable();
    expect(router.url).toBe('/sign-in');
    expect(TestBed.inject(AuthService).pendingReturnTo()).toBe('/more/income');
  });

  it('a deliberate sign-out is not treated as a lost session (no return path kept)', async () => {
    const f = TestBed.createComponent(AppComponent);
    const router = TestBed.inject(Router);
    TestBed.inject(AuthSessionStore).save(tokenResponse(USER_A, 'r'));
    f.detectChanges();
    await router.navigateByUrl('/more/income');
    f.detectChanges();
    await TestBed.inject(AuthService).signOut();
    f.detectChanges();
    await f.whenStable();
    expect(router.url).toBe('/sign-in');
    expect(TestBed.inject(AuthService).pendingReturnTo()).toBe('/home');
  });

  it('starting signed out does not trigger the session-lost redirect (the guard handles it)', () => {
    const f = TestBed.createComponent(AppComponent);
    const nav = spyOn(TestBed.inject(Router), 'navigateByUrl').and.callThrough();
    f.detectChanges();
    expect(nav).not.toHaveBeenCalled();
  });

});

describe('service worker registration', () => {
  it('is enabled in production and registers once the app is stable', () => {
    const o = serviceWorkerOptions(true);
    expect(o.enabled).toBeTrue();
    expect(o.registrationStrategy).toBe('registerWhenStable:30000');
  });

  it('is disabled outside production', () => {
    expect(serviceWorkerOptions(false).enabled).toBeFalse();
  });
});
