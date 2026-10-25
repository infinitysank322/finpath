import { APP_INITIALIZER, ApplicationConfig, inject, isDevMode, provideZoneChangeDetection } from '@angular/core';
import { ExpenseQueueService } from './expense/expense-queue.service';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { authInterceptor } from './auth/auth.interceptor';
import { provideRouter } from '@angular/router';
import { SwRegistrationOptions, provideServiceWorker } from '@angular/service-worker';

import { routes } from './app.routes';

/** Registration only. Shell caching is ngsw-config.json; the last dashboard is cached by DashboardCacheService (T79). */
export function serviceWorkerOptions(production: boolean): SwRegistrationOptions {
  return { enabled: production, registrationStrategy: 'registerWhenStable:30000' };
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideHttpClient(withInterceptors([authInterceptor])),
    // Start the offline queue at app start: flushes now and on every 'online' event (not awaited, never blocks bootstrap).
    {
      provide: APP_INITIALIZER,
      multi: true,
      useFactory: () => {
        const queue = inject(ExpenseQueueService);
        return () => queue.start();
      },
    },
    provideServiceWorker('ngsw-worker.js', serviceWorkerOptions(!isDevMode())),
  ],
};
