import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { API_BASE } from '../api-base';
import { memoryStorage } from '../expense/queue-testing';
import { challengeFor } from './pkce';
import { AuthService, BrowserNav, SIGNIN_KEY, SIGNIN_STORAGE } from './auth.service';
import { SignInComponent } from './sign-in.component';
import { AUTH_STORAGE } from './session.store';

/** WCAG relative luminance from a computed `rgb(r, g, b)` string. */
function lum(rgb: string): number {
  const [r, g, b] = (rgb.match(/\d+(\.\d+)?/g) ?? []).slice(0, 3).map((v) => {
    const s = Number(v) / 255;
    return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  });
  return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}
const contrast = (a: string, b: string) => {
  const [hi, lo] = [lum(a), lum(b)].sort((x, y) => y - x);
  return (hi + 0.05) / (lo + 0.05);
};

/** crypto.subtle resolves outside the zone, so whenStable() does not wait for it: poll for the outcome. */
async function until(pred: () => boolean, ms = 2000): Promise<void> {
  const end = Date.now() + ms;
  while (!pred() && Date.now() < end) await new Promise((r) => setTimeout(r, 5));
}

describe('SignInComponent', () => {
  let signin: ReturnType<typeof memoryStorage>;
  let assign: jasmine.Spy;

  const render = (storage: unknown = signin) => {
    TestBed.configureTestingModule({
      imports: [SignInComponent],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: API_BASE, useValue: 'http://localhost:8080' },
        { provide: SIGNIN_STORAGE, useValue: storage },
        { provide: AUTH_STORAGE, useValue: memoryStorage() },
        { provide: BrowserNav, useValue: { origin: () => 'http://localhost:4200', assign } },
      ],
    });
    const f = TestBed.createComponent(SignInComponent);
    f.detectChanges();
    return f;
  };
  const button = (root: HTMLElement) => root.querySelector('button') as HTMLButtonElement;

  beforeEach(() => {
    signin = memoryStorage();
    assign = jasmine.createSpy('assign');
  });
  afterEach(() => delete document.documentElement.dataset['theme']);

  for (const theme of ['dark', 'light']) {
    it(`renders at 380 px in ${theme} with no horizontal overflow, one labelled >= 44 px button with AA contrast`, () => {
      document.documentElement.dataset['theme'] = theme;
      const f = render();
      const root: HTMLElement = f.nativeElement;
      const host = document.createElement('div');
      host.style.width = '380px';
      host.appendChild(root);
      document.body.appendChild(host);
      try {
        expect(root.scrollWidth).toBeLessThanOrEqual(380);
        expect(host.scrollWidth).toBeLessThanOrEqual(380);
        const buttons = root.querySelectorAll('button');
        expect(buttons.length).toBe(1);
        const b = buttons[0];
        expect(b.textContent!.trim()).toBe('Continue with Google');
        expect(b.getBoundingClientRect().height).toBeGreaterThanOrEqual(44);
        const css = getComputedStyle(b);
        expect(contrast(css.color, css.backgroundColor)).toBeGreaterThanOrEqual(4.5);
        const card = getComputedStyle(root.querySelector('.auth-card')!);
        const p = getComputedStyle(root.querySelector('p')!);
        expect(contrast(p.color, card.backgroundColor)).toBeGreaterThanOrEqual(4.5);
        expect(root.querySelector('h1')!.textContent).toContain('Sign in');
        expect(root.querySelector('img')).toBeNull(); // no Google brand asset
      } finally {
        host.remove();
      }
    });
  }

  it('starts the round trip: stores {verifier, state, returnTo} and navigates to the API with S256 PKCE', async () => {
    const f = render();
    TestBed.inject(AuthService).rememberReturnTo('/debt/payoff');
    button(f.nativeElement).click();
    await until(() => assign.calls.count() > 0);

    expect(assign).toHaveBeenCalledTimes(1);
    const url = new URL(assign.calls.mostRecent().args[0]);
    expect(url.origin + url.pathname).toBe('http://localhost:8080/oauth2/authorization/google');
    const stored = JSON.parse(signin.data[SIGNIN_KEY]);
    expect(Object.keys(stored).sort()).toEqual(['returnTo', 'state', 'verifier']);
    expect(stored.returnTo).toBe('/debt/payoff');
    expect(stored.verifier).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(url.searchParams.get('redirect_uri')).toBe('http://localhost:4200/auth/callback');
    expect(url.searchParams.get('code_challenge')).toBe(await challengeFor(stored.verifier));
    expect(url.searchParams.get('code_challenge_method')).toBe('S256');
    expect(url.searchParams.get('state')).toBe(stored.state);
    expect(url.searchParams.get('state')).toMatch(/^[A-Za-z0-9._~-]{1,128}$/);
    expect(url.search).not.toContain(stored.verifier); // the verifier never leaves the device
    expect(url.search).toContain('redirect_uri=http%3A%2F%2Flocalhost%3A4200%2Fauth%2Fcallback');
  });

  it('a double tap starts only one round trip', async () => {
    const f = render();
    button(f.nativeElement).click();
    button(f.nativeElement).click();
    await until(() => assign.calls.count() > 1, 300); // give a second round trip every chance to start
    expect(assign).toHaveBeenCalledTimes(1);
  });

  it('blocked sessionStorage: does not navigate, shows a message', async () => {
    const f = render({
      getItem: () => null,
      setItem: () => {
        throw new Error('blocked');
      },
      removeItem: () => undefined,
    });
    button(f.nativeElement).click();
    await until(() => !f.componentInstance.busy());
    f.detectChanges();
    expect(assign).not.toHaveBeenCalled();
    expect(f.nativeElement.querySelector('[role=alert]').textContent).toContain('browser storage');
    expect(button(f.nativeElement).disabled).toBeFalse();
  });
});
