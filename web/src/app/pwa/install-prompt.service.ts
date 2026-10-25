import { Injectable, signal } from '@angular/core';

/** The non-standard Chromium event; not in lib.dom. */
interface BeforeInstallPromptEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: 'accepted' | 'dismissed' }>;
}

/** Captures the browser's install offer so the app can show its own button instead of an unprompted banner. */
@Injectable({ providedIn: 'root' })
export class InstallPromptService {
  private deferred: BeforeInstallPromptEvent | null = null;
  readonly canInstall = signal(false);

  constructor() {
    if (typeof window === 'undefined') return;
    window.addEventListener('beforeinstallprompt', (e) => {
      e.preventDefault();
      this.deferred = e as BeforeInstallPromptEvent;
      this.canInstall.set(true);
    });
    window.addEventListener('appinstalled', () => this.clear());
  }

  async install(): Promise<void> {
    const d = this.deferred;
    if (!d) return;
    await d.prompt();
    await d.userChoice;
    this.clear(); // the event is single-use whatever the choice
  }

  private clear(): void {
    this.deferred = null;
    this.canInstall.set(false);
  }
}
