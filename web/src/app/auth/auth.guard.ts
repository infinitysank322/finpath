import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from './auth.service';
import { AuthSessionStore } from './session.store';

/** Every app route: signed out -> /sign-in, remembering the attempted URL for after sign-in. */
export const authGuard: CanActivateFn = (_route, state) => {
  if (inject(AuthSessionStore).isSignedIn()) return true;
  inject(AuthService).rememberReturnTo(state.url);
  return inject(Router).createUrlTree(['/sign-in']);
};

/** /sign-in: a signed-in user goes to /home instead. */
export const signedOutGuard: CanActivateFn = () =>
  inject(AuthSessionStore).isSignedIn() ? inject(Router).createUrlTree(['/home']) : true;
