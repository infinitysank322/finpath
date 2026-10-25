import { InjectionToken } from '@angular/core';
import { KeyValueStorage } from './session.store';

export const SIGNIN_KEY = 'finpath.signin.v1';

/** sessionStorage seam: the PKCE verifier lives here only for the Google round trip. */
export const SIGNIN_STORAGE = new InjectionToken<KeyValueStorage | null>('SIGNIN_STORAGE', {
  providedIn: 'root',
  factory: () => {
    try {
      return globalThis.sessionStorage ?? null;
    } catch {
      return null;
    }
  },
});
