import { TestBed } from '@angular/core/testing';
import { THEME_KEY, ThemeService } from './theme.service';

describe('ThemeService', () => {
  beforeEach(() => localStorage.removeItem(THEME_KEY));
  afterEach(() => {
    localStorage.removeItem(THEME_KEY);
    document.documentElement.removeAttribute('data-theme');
  });

  it('defaults to dark when nothing is saved, whatever the OS prefers', () => {
    spyOn(window, 'matchMedia').and.returnValue({ matches: false } as MediaQueryList);
    const s = TestBed.inject(ThemeService);
    expect(s.theme()).toBe('dark');
    expect(document.documentElement.dataset['theme']).toBe('dark');
  });

  it('a saved choice wins over the default', () => {
    localStorage.setItem(THEME_KEY, 'light');
    const s = TestBed.inject(ThemeService);
    expect(s.theme()).toBe('light');
    expect(document.documentElement.dataset['theme']).toBe('light');
  });

  it('ignores a corrupt saved value', () => {
    localStorage.setItem(THEME_KEY, 'purple');
    expect(TestBed.inject(ThemeService).theme()).toBe('dark');
  });

  it('toggle() flips, applies and persists; set() persists', () => {
    const s = TestBed.inject(ThemeService);
    s.toggle();
    expect(s.theme()).toBe('light');
    expect(document.documentElement.dataset['theme']).toBe('light');
    expect(localStorage.getItem(THEME_KEY)).toBe('light');
    s.set('dark');
    expect(localStorage.getItem(THEME_KEY)).toBe('dark');
  });
});
