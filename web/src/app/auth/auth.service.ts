import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, InjectionToken, inject } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { API_BASE } from '../api-base';
import { AuthApiService } from './auth-api.service';
import { LocalDataService } from './local-data.service';
import { SIGNIN_KEY, SIGNIN_STORAGE } from './signin-storage';
import { challengeFor, createState, createVerifier } from './pkce';
import { DEFAULT_RETURN_PATH, safeReturnPath } from './return-path';
import { AuthSessionStore, Session, isExpired, isTokenResponse } from './session.store';

export { SIGNIN_KEY, SIGNIN_STORAGE };

/** Cross-tab mutual exclusion for refresh (a replayed, already-rotated refresh token revokes the whole family). */
export type RefreshLock = <T>(fn: () => Promise<T>) => Promise<T>;
export const REFRESH_LOCK = new InjectionToken<RefreshLock>('REFRESH_LOCK', {
  providedIn: 'root',
  factory: () => {
    const locks = globalThis.navigator?.locks;
    return locks
      ? <T>(fn: () => Promise<T>) => locks.request('finpath.auth.refresh', fn) as Promise<T>
      : <T>(fn: () => Promise<T>) => fn();
  },
});

/** Wrapper over full-page navigation so specs can spy on it. */
@Injectable({ providedIn: 'root' })
export class BrowserNav {
  origin(): string {
    return globalThis.location.origin;
  }
  assign(url: string): void {
    globalThis.location.assign(url);
  }
}

export interface SignInEntry {
  verifier: string;
  state: string;
  returnTo: string;
}

function isEntry(x: unknown): x is SignInEntry {
  const o = x as SignInEntry | null;
  return !!o && typeof o.verifier === 'string' && typeof o.state === 'string' && typeof o.returnTo === 'string';
}

/** A refresh was asked for but there is no session (nothing to refresh or sign out of). */
export class NoSessionError extends Error {
  constructor() {
    super('no session');
  }
}

/** The stored session now belongs to a different user than the request that failed. */
export class UserChangedError extends Error {
  constructor() {
    super('user changed');
  }
}

@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly store = inject(AuthSessionStore);
  private readonly api = inject(AuthApiService);
  private readonly router = inject(Router);
  private readonly nav = inject(BrowserNav);
  private readonly signinStorage = inject(SIGNIN_STORAGE);
  private readonly lock = inject(REFRESH_LOCK);
  private readonly local = inject(LocalDataService);
  private readonly base = inject(API_BASE);

  /** The URL the guard intercepted; kept in memory and in the round-trip entry, never in a query string. */
  private returnTo: string | null = null;
  private inFlight: Promise<Session> | null = null;
  private inFlightUser: string | null = null;
  /** Set by signOut() so the session-lost redirect does not treat a deliberate sign-out as an expiry. */
  private deliberateSignOut = false;

  rememberReturnTo(url: string): void {
    this.returnTo = safeReturnPath(url);
  }

  pendingReturnTo(): string {
    return this.returnTo ?? DEFAULT_RETURN_PATH;
  }

  /**
   * Builds PKCE + state, stores them for the round trip only, and leaves the app for Google via the API.
   * Resolves false (and does not navigate) when the round trip cannot be done safely on this device.
   */
  async startSignIn(returnTo: string = this.pendingReturnTo()): Promise<boolean> {
    let verifier: string;
    let state: string;
    let challenge: string;
    try {
      verifier = createVerifier();
      state = createState();
      challenge = await challengeFor(verifier);
    } catch {
      return false; // no WebCrypto (insecure context)
    }
    const entry: SignInEntry = { verifier, state, returnTo: safeReturnPath(returnTo) };
    try {
      if (!this.signinStorage) return false;
      this.signinStorage.setItem(SIGNIN_KEY, JSON.stringify(entry));
      if (this.signinStorage.getItem(SIGNIN_KEY) === null) return false;
    } catch {
      return false; // without the stored verifier the callback could never complete
    }
    const q = new URLSearchParams({
      redirect_uri: `${this.nav.origin()}/auth/callback`,
      code_challenge: challenge,
      code_challenge_method: 'S256',
      state,
    });
    this.nav.assign(`${this.base}/oauth2/authorization/google?${q.toString()}`);
    return true;
  }

  /** Reads AND removes the round-trip entry, so a reload or a second callback cannot reuse it. */
  takeSignInEntry(): SignInEntry | null {
    let raw: string | null = null;
    try {
      raw = this.signinStorage?.getItem(SIGNIN_KEY) ?? null;
    } catch {
      raw = null;
    }
    try {
      this.signinStorage?.removeItem(SIGNIN_KEY);
    } catch {
      /* ignore */
    }
    if (!raw) return null;
    try {
      const parsed: unknown = JSON.parse(raw);
      return isEntry(parsed) ? parsed : null;
    } catch {
      return null;
    }
  }

  /**
   * Called when the session whose refresh token was `staleRefreshToken` is no good any more (a 401, or an
   * expired access token). Re-reads storage first: if another tab already rotated it, that session is used
   * instead of replaying the stale token. Concurrent callers share one in-flight refresh.
   * A 401 from /auth/refresh clears the session and routes to /sign-in; a network error or 5xx keeps it.
   */
  refreshAfter(stale: Session): Promise<Session> {
    const fresh = this.rotatedElsewhere(stale);
    if (fresh) return Promise.resolve(fresh);
    if (this.inFlight && this.inFlightUser === stale.userId) return this.inFlight;
    if (this.inFlight) return Promise.reject(new UserChangedError()); // a refresh for someone else is running
    this.inFlightUser = stale.userId;
    this.inFlight = this.lock(() => this.doRefresh(stale)).finally(() => {
      this.inFlight = null;
      this.inFlightUser = null;
    });
    return this.inFlight;
  }

  /**
   * Sign-out. Best-effort POST /auth/logout revokes the refresh-token family; its outcome never blocks or fails
   * the sign-out. Unsent expenses stay under the user's own key unless `discardQueue` is set, which removes them
   * (and any draft that user started) so nothing is left on a shared device.
   */
  signOut(opts: { discardQueue?: boolean } = {}): Promise<boolean> {
    this.deliberateSignOut = true;
    const s = this.store.session();
    if (s) this.api.logout(s.refreshToken).subscribe({ error: () => undefined });
    if (opts.discardQueue) this.local.wipeUser(s?.userId ?? null);
    else this.store.clear();
    this.returnTo = null;
    return this.router.navigateByUrl('/sign-in');
  }

  /** `DELETE /me` succeeded: the account is gone, so everything stored here for it goes too. */
  accountDeleted(): Promise<boolean> {
    return this.endAndWipe();
  }

  /** Consent was withdrawn: same local wipe as a deleted account. */
  consentWithdrawn(): Promise<boolean> {
    return this.endAndWipe();
  }

  private endAndWipe(): Promise<boolean> {
    this.deliberateSignOut = true;
    this.local.wipeUser(this.store.session()?.userId ?? null);
    this.returnTo = null;
    return this.router.navigateByUrl('/sign-in');
  }

  /**
   * The session disappeared without this tab asking (another tab signed out, or a refresh was rejected):
   * remember where the user was and go to /sign-in. A deliberate signOut() is skipped (it routes itself).
   */
  sessionLost(currentUrl: string): void {
    if (this.deliberateSignOut) {
      this.deliberateSignOut = false;
      return;
    }
    const path = currentUrl.split(/[?#]/)[0];
    if (path === '/sign-in' || path === '/auth/callback') return;
    this.rememberReturnTo(currentUrl);
    void this.router.navigateByUrl('/sign-in');
  }

  /** A session exists again: a later loss is not the deliberate sign-out any more. */
  sessionPresent(): void {
    this.deliberateSignOut = false;
  }

  /** A session another tab rotated for the SAME user. A different user's session is never borrowed. */
  private rotatedElsewhere(stale: Session): Session | null {
    const s = this.store.reload();
    return s && s.userId === stale.userId && s.refreshToken !== stale.refreshToken && !isExpired(s) ? s : null;
  }

  private async doRefresh(stale: Session): Promise<Session> {
    // Inside the lock: another tab may have finished a refresh while this one waited.
    const fresh = this.rotatedElsewhere(stale);
    if (fresh) return fresh;
    const current = this.store.session();
    if (!current) throw new NoSessionError();
    // Someone else signed in meanwhile: never refresh or retry this request with their tokens.
    if (current.userId !== stale.userId) throw new UserChangedError();
    let body: unknown;
    try {
      body = await firstValueFrom(this.api.refresh(current.refreshToken));
    } catch (e) {
      const rejected = e instanceof HttpErrorResponse && e.status === 401;
      // Only a definitive rejection of the token we sent signs out; offline or 5xx keeps the session.
      if (rejected && this.store.reload()?.refreshToken === current.refreshToken) {
        this.rememberReturnTo(this.router.url);
        this.store.clear();
        void this.router.navigateByUrl('/sign-in');
      }
      throw e;
    }
    if (!isTokenResponse(body)) throw new HttpErrorResponse({ status: 502, statusText: 'Bad token response' });
    return this.store.save(body);
  }
}
