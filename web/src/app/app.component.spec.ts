import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { AppComponent } from './app.component';
import { routes } from './app.routes';
import { serviceWorkerOptions } from './app.config';
import { THEME_KEY, ThemeService } from './theme.service';

describe('AppComponent shell', () => {
  beforeEach(async () => {
    localStorage.removeItem(THEME_KEY);
    await TestBed.configureTestingModule({
      imports: [AppComponent],
      providers: [provideRouter(routes)],
    }).compileComponents();
  });

  it('renders a labelled bottom nav with five destinations', () => {
    const f = TestBed.createComponent(AppComponent);
    f.detectChanges();
    const nav = f.nativeElement.querySelector('nav[aria-label="Primary"]') as HTMLElement;
    const labels = Array.from(nav.querySelectorAll('a')).map((a) => a.textContent?.trim());
    expect(labels).toEqual(['Home', 'Spending', 'Debt', 'Goals', 'More']);
  });

  it('theme toggle flips data-theme, persists it and updates aria-pressed', () => {
    const f = TestBed.createComponent(AppComponent);
    TestBed.inject(ThemeService).set('light');
    f.detectChanges();
    const btn = f.nativeElement.querySelector('button.theme-toggle') as HTMLButtonElement;
    expect(btn.getAttribute('aria-pressed')).toBe('false');
    btn.click();
    f.detectChanges();
    expect(document.documentElement.dataset['theme']).toBe('dark');
    expect(localStorage.getItem(THEME_KEY)).toBe('dark');
    expect(btn.getAttribute('aria-pressed')).toBe('true');
    btn.click();
    expect(document.documentElement.dataset['theme']).toBe('light');
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
