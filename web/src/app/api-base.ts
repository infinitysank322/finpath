import { InjectionToken } from '@angular/core';
import { environment } from '../environments/environment';

/** Origin (no trailing slash) every *-api.service prefixes its paths with; '' = same origin. */
export const API_BASE = new InjectionToken<string>('API_BASE', {
  providedIn: 'root',
  factory: () => environment.apiBase.replace(/\/+$/, ''),
});
