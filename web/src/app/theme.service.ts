import { DOCUMENT } from '@angular/common';
import { Injectable, inject, signal } from '@angular/core';

export type Theme = 'light' | 'dark';
export const THEME_KEY = 'finpath.theme';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly doc = inject(DOCUMENT);
  readonly theme = signal<Theme>(this.initial());

  constructor() {
    this.apply(this.theme());
  }

  toggle(): void {
    this.set(this.theme() === 'dark' ? 'light' : 'dark');
  }

  set(theme: Theme): void {
    this.theme.set(theme);
    try {
      localStorage.setItem(THEME_KEY, theme);
    } catch {
      /* storage unavailable (private mode); theme still applies for the session */
    }
    this.apply(theme);
  }

  private apply(theme: Theme): void {
    this.doc.documentElement.dataset['theme'] = theme;
    // Browser chrome and native controls follow the chosen theme, not the OS.
    this.doc.querySelector('meta[name=theme-color]')?.setAttribute('content', theme === 'dark' ? '#0b0b0c' : '#ffffff');
    this.doc.querySelector('meta[name=color-scheme]')?.setAttribute('content', theme);
  }

  private initial(): Theme {
    try {
      const saved = localStorage.getItem(THEME_KEY);
      if (saved === 'light' || saved === 'dark') return saved;
    } catch {
      /* ignore */
    }
    return 'dark';
  }
}
