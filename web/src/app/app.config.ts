import { ApplicationConfig, isDevMode, provideZoneChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { SwRegistrationOptions, provideServiceWorker } from '@angular/service-worker';

import { routes } from './app.routes';

/** Registration only. Caching strategy beyond ngsw defaults is T79. */
export function serviceWorkerOptions(production: boolean): SwRegistrationOptions {
  return { enabled: production, registrationStrategy: 'registerWhenStable:30000' };
}

export const appConfig: ApplicationConfig = {
  providers: [
    provideZoneChangeDetection({ eventCoalescing: true }),
    provideRouter(routes),
    provideServiceWorker('ngsw-worker.js', serviceWorkerOptions(!isDevMode())),
  ],
};
