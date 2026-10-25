import { Injectable, InjectionToken, OnDestroy, computed, inject, signal } from '@angular/core';

export const SESSION_KEY = 'finpath.session.v1';
/** Refresh this long before the access token's stated expiry (clock skew, request latency). */
export const EXPIRY_SKEW_MS = 30_000;

export type KeyValueStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>;

/** localStorage seam: survives an app restart and the Capacitor webview. null = storage blocked. */
export const AUTH_STORAGE = new InjectionToken<KeyValueStorage | null>('AUTH_STORAGE', {
  providedIn: 'root',
  factory: () => {
    try {
      return globalThis.localStorage ?? null;
    } catch {
      return null;
    }
  },
});

/** Shape of POST /auth/token and POST /auth/refresh 200 bodies (T29). */
export interface TokenResponse {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  refreshToken: string;
}

export interface Session {
  accessToken: string;
  /** Epoch ms. */
  expiresAt: number;
  refreshToken: string;
  /** JWT `sub`, decoded WITHOUT verification. Only names local storage keys; never sent as an identity. */
  userId: string | null;
}

export function isTokenResponse(x: unknown): x is TokenResponse {
  const o = x as TokenResponse | null;
  return (
    !!o &&
    typeof o.accessToken === 'string' &&
    o.accessToken.length > 0 &&
    typeof o.refreshToken === 'string' &&
    o.refreshToken.length > 0 &&
    typeof o.expiresIn === 'number' &&
    Number.isFinite(o.expiresIn) &&
    o.expiresIn > 0
  );
}

function isSession(x: unknown): x is Session {
  const o = x as Session | null;
  return (
    !!o &&
    typeof o.accessToken === 'string' &&
    typeof o.refreshToken === 'string' &&
    o.refreshToken.length > 0 &&
    typeof o.expiresAt === 'number' &&
    (o.userId === null || typeof o.userId === 'string')
  );
}

/** Reads `sub` from a JWT payload without verifying it. Restricted to a safe key fragment, else null. */
export function subjectOf(jwt: string): string | null {
  try {
    const part = jwt.split('.')[1];
    if (!part) return null;
    const b64 = part.replace(/-/g, '+').replace(/_/g, '/');
    const json = atob(b64 + '='.repeat((4 - (b64.length % 4)) % 4));
    const sub: unknown = JSON.parse(json)?.sub;
    return typeof sub === 'string' && /^[A-Za-z0-9-]{1,64}$/.test(sub) ? sub : null;
  } catch {
    return null;
  }
}

export function isExpired(s: Session, now = Date.now()): boolean {
  return s.expiresAt - EXPIRY_SKEW_MS <= now;
}

/**
 * The signed-in session, persisted in localStorage so a new instance (app restart) is already signed in.
 * Storage access never throws; tokens are never logged.
 */
@Injectable({ providedIn: 'root' })
export class AuthSessionStore implements OnDestroy {
  private readonly storage = inject(AUTH_STORAGE);
  private readonly _session = signal<Session | null>(this.read());
  readonly session = this._session.asReadonly();
  /** A stored session with a refresh token. Access-token expiry is not checked: it is refreshed on use. */
  readonly isSignedIn = computed(() => this._session() !== null);
  readonly userId = computed(() => this._session()?.userId ?? null);

  private readonly onStorage = (e: StorageEvent) => {
    if (e.key === null || e.key === SESSION_KEY) this.reload();
  };

  constructor() {
    globalThis.addEventListener?.('storage', this.onStorage);
  }

  ngOnDestroy(): void {
    globalThis.removeEventListener?.('storage', this.onStorage);
  }

  save(t: TokenResponse, now = Date.now()): Session {
    const s: Session = {
      accessToken: t.accessToken,
      expiresAt: now + t.expiresIn * 1000,
      refreshToken: t.refreshToken,
      userId: subjectOf(t.accessToken),
    };
    this._session.set(s);
    try {
      this.storage?.setItem(SESSION_KEY, JSON.stringify(s));
    } catch {
      /* quota/blocked: signed in for this run only */
    }
    return s;
  }

  clear(): void {
    this._session.set(null);
    try {
      this.storage?.removeItem(SESSION_KEY);
    } catch {
      /* ignore */
    }
  }

  /** Re-read storage (another tab may have rotated or cleared the session). */
  reload(): Session | null {
    const s = this.storage ? this.read() : this._session();
    this._session.set(s);
    return s;
  }

  private read(): Session | null {
    try {
      const raw = this.storage?.getItem(SESSION_KEY);
      if (!raw) return null;
      const parsed: unknown = JSON.parse(raw);
      return isSession(parsed) ? parsed : null;
    } catch {
      return null;
    }
  }
}
