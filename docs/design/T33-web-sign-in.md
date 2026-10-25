# T33 - Web Google sign-in, session, auth guard

Status: design before build, branch `fr-01/t33-web-sign-in`. Builds on the T29 contract
(`docs/design/T29-google-oauth-jwt.md`, sections 1, 2, 8, 9) without changing it. Work is mostly in
`web/`; the API change is CORS on the API chain only. No rules (no money maths), no new tables.
`[?OQ-4]`: origins, hosts and return URLs are config only.

## 1. Decisions (settled, recorded here)

1. **CORS on the API chain, not an Angular dev proxy.** Proxying API paths collides with SPA routes
   (API `/goals` vs SPA `/goals`), and Capacitor (T43b, origin `https://localhost`) and an
   independently hosted web tier (T84) are cross-origin anyway.
2. **`API_BASE` from Angular environment files** via `fileReplacements`: development
   `http://localhost:8080`; production `''` (same-origin placeholder, T84 sets the real host, OQ-4).
3. **PKCE + state generated in the web client**, verifier held in `sessionStorage` for the round trip only.
4. **Session in `localStorage`**, so it survives an app restart and the Capacitor webview. Bearer
   interceptor, a single shared refresh on 401, client-side sign-out.
5. **Google console (local):** one Web application client; authorised redirect URI
   `http://localhost:8080/login/oauth2/code/google`; no authorised JavaScript origins (the browser
   never calls Google from JS); while the consent screen is in Testing, the owner's Gmail is a test user.
6. **Accepted risk:** an XSS can read the refresh token in `localStorage`. Mitigations: rotation with
   family revocation on replay (T29), 15-minute access tokens, a strict CSP later (T79/T84). T43b
   may move the token to secure storage on Android.

## 2. API change: CORS (`SecurityConfig.apiChain`, `@Order(2)` only)

- `http.cors(...)` with a `CorsConfigurationSource` built from `FINPATH_CORS_ALLOWED_ORIGINS`
  (comma-separated, **exact** origin match; `application.yml` default `http://localhost:4200`).
  An unset or empty `FINPATH_CORS_ALLOWED_ORIGINS` means no cross-origin caller is allowed (startup
  does **not** fail; same-origin hosting needs no CORS). Startup fails on `*`, any wildcard, `null`,
  a blank entry inside the list, or an entry that is not a bare origin (`scheme://host[:port]`, no
  path, no trailing slash, no user info, query or fragment).
- `allowCredentials=false` (bearer header only, no cookies). Methods `GET, POST, PUT, PATCH, DELETE`.
  Allowed headers `Authorization, Content-Type, Idempotency-Key`. No exposed headers. Preflight
  `maxAge` 3600 s.
- The `CorsFilter` runs before authentication. A preflight to an authenticated path needs no token,
  and the JSON 401 for an allowed origin carries `Access-Control-Allow-Origin`. That lets the
  interceptor see a real 401 instead of an opaque network error.
- A disallowed origin gets no ACAO header (its preflight gets a 403).
- **Not on the Google login chain.** `/oauth2/**` and `/login/**` are reached by top-level
  navigation, so they need no CORS.
- `.env.example` ships `FINPATH_CORS_ALLOWED_ORIGINS=http://localhost:4200`, and `compose.yaml`
  passes it through to the api service with the same default. Locally,
  `FINPATH_AUTH_ALLOWED_REDIRECTS` must contain `http://localhost:4200/auth/callback`.

## 3. Web: sign-in round trip

```
/sign-in  "Continue with Google"
  verifier  = base64url(32 bytes from crypto.getRandomValues)          -> 43 chars
  challenge = base64url(SHA-256(verifier)) via crypto.subtle.digest
  state     = base64url(>= 16 random bytes)
  sessionStorage['finpath.signin.v1'] = {verifier, state, returnTo}
  location.assign(`${API_BASE}/oauth2/authorization/google?redirect_uri=${origin}/auth/callback
                   &code_challenge=…&code_challenge_method=S256&state=…`)   (all values URL-encoded)
/auth/callback?code=…&state=…  (or ?error=…&state=…)
  1 read AND remove the sessionStorage entry (a reload cannot replay it)
  2 history.replaceState to a bare /auth/callback (the code leaves the URL immediately)
  3 entry missing, state !== entry.state, ?error present, or no code -> /sign-in with a message;
    the API is NOT called
  4 POST /auth/token {code, codeVerifier: entry.verifier} -> store the session
  5 router.navigateByUrl(returnTo, {replaceUrl: true}); returnTo only if it starts with '/' and
    not '//', else /home
```

- `crypto.subtle` needs a secure context. `http://localhost` and Capacitor's `https://localhost` both are one.
- Messages are fixed strings keyed by code: `email_not_verified`, `login_failed`, a state mismatch
  or missing entry ("Sign-in expired, try again"), and `invalid_grant` from `/auth/token`. A code
  value, token or email is never shown or logged.
- `returnTo` is the URL the guard intercepted. It is stored in the sessionStorage entry, never in a query string.

## 4. Web: session, interceptor, guard

- `AuthSessionService` holds `{accessToken, expiresAt, refreshToken, userId}` in `localStorage` key
  `finpath.session.v1`. `expiresAt = now + expiresIn*1000`. `userId` is the JWT `sub` (a UUID),
  base64url-decoded without verification. It is used only to namespace local data (below), never
  sent as an identity. The service reads storage on construction, so a new instance (app restart)
  is already signed in. A `storage` event keeps tabs in step.
- **Interceptor** (functional, `withInterceptors`). It attaches `Authorization: Bearer` only when
  the URL starts with `API_BASE + '/'`. When `API_BASE` is `''`, it attaches only to relative URLs
  that start with `/` but not `//`, so a bare-prefix match never leaks the token to another host.
  It never attaches to `/auth/token` or `/auth/refresh`.
- **On a 401** from any other API call, the interceptor joins one shared in-flight
  `POST /auth/refresh` and retries the original request once with the new token. Before it
  refreshes, it re-reads storage: if another tab already rotated the token, it uses the stored
  token instead of replaying the old one (a replay would revoke the whole family). If the refresh
  itself returns 401 `invalid_grant`, the session is cleared and the app routes to `/sign-in`. A
  network failure or 5xx on refresh keeps the session and passes the error through, so going
  offline never signs anyone out.
- **Guard** (`canActivate`) on every route except `/sign-in` and `/auth/callback`. "Signed in"
  means a stored session with a refresh token; expiry is not checked, because an expired access
  token is refreshed on use and works offline. Signed out: redirect to `/sign-in` and save the
  intended URL as `returnTo`. `/sign-in` while signed in goes to `/home`. The `**` catch-all stays
  behind the guard.
- **Sign-out** (on the More screen) clears the session in the client only. No server revoke endpoint exists (section 7).
- **T43 offline queue dependencies** (`docs/design/T43-offline-queue.md`):
  - Namespace the queue key by `userId`: `finpath.expenseQueue.v1.<userId>`.
  - Call `ExpenseQueueService.flush()` once the session is stored.
  - Sign-out keeps the signed-out user's namespaced queue for their next sign-in. If unsent items
    exist, the user is warned with a count first.
  - The queue already retries a 401, so nothing queued is lost while signed out.

## 5. Screens

- **`/sign-in`**: app name, one-line purpose, a single "Continue with Google" button, and a
  message area (`role="alert"`) for the codes above. The shell's bottom nav is hidden on
  `/sign-in` and `/auth/callback`.
- **`/auth/callback`**: a "Signing you in" status (`aria-live`), or the error message with a
  "Try again" link to `/sign-in`.
- Both are checked at 380 px in light and dark, with no horizontal scroll and a contrast of at
  least 4.5:1. No figures are shown, so neither screen reads any amount.

## 6. Tests (acceptance mapping)

| Acceptance | Test |
| --- | --- |
| Signed-out redirect | guard spec: no session, navigate `/spending/add` -> `/sign-in`, `returnTo` saved; `/sign-in` and `/auth/callback` reachable signed out |
| Token survives restart | write a session, create a **new** `AuthSessionService` instance, it reports signed in with the same tokens |
| 380 px dark | sign-in component spec at 380 px with the dark theme: no horizontal overflow, button labelled |
| CORS | API `CorsTest`: an allowed origin's preflight to an authenticated path with no token -> 200 and `ACAO=<origin>`, no `Access-Control-Allow-Credentials`; another origin -> 403, no ACAO; a GET with no token from the allowed origin -> 401 carrying ACAO; `/oauth2/authorization/google` returns no ACAO; startup fails for `*` and for an origin with a path |
| State mismatch | callback spec: mismatched or missing state, or `?error=`, gives no HTTP call (`HttpTestingController.expectNone`), the entry is removed, and the user lands on `/sign-in` |
| `codeVerifier` sent | callback spec: the body is exactly `{code, codeVerifier}`, with the stored verifier; PKCE spec: verifier 43 chars `[A-Za-z0-9_-]`, challenge equals the RFC 7636 appendix B vector |
| (extra) interceptor | Bearer only on the API_BASE prefix; never on `/auth/*` or a foreign host; two concurrent 401s -> one refresh, both retried once; refresh 401 -> cleared and routed to `/sign-in`; refresh network error -> session kept |

## 7. Open questions (assumptions)

- **OQ-4 / T84 same-origin hosting.** If the web and API share an origin, the ngsw service worker
  would answer the navigation to `/oauth2/**` and `/login/**` with `index.html`. *Assume*
  T79/T84 exclude those paths in `ngsw-config.json` `navigationUrls`. This is flagged for T79.
- **Legacy unnamespaced queue** (`finpath.expenseQueue.v1`). Before T33, no item could have synced
  (every call returned 401). *Assume* the first sign-in adopts the legacy items into that user's
  namespace and deletes the legacy key, because the MVP has a single owner per device.
  **As built:** adoption is one-time. A marker key `finpath.expenseQueue.migrated` is set at the
  first signed-in queue access (whether or not legacy items existed), so a later user never adopts
  anything. After T33 nothing is ever written under the legacy key: enqueue while signed out is
  refused, and the add-expense screen keeps the form and offers "Sign in to save", remembering the
  return path.
- **Server-side sign-out.** No endpoint routes onto `RefreshTokenStore` family revoke or
  `revokeAll`. *Assume* sign-out is client-only for now. A follow-up adds `POST /auth/logout`
  `{refreshToken}`, which revokes the family.
- **Consent gate.** A first-time user lands on `returnTo` without having consented. *Assume* T46
  adds the consent/onboarding redirect on top of this guard. T33 does not gate on consent.

## Coverage

PLAN.md tasks: **T33** (fully: the sign-in, storage, guard and restart acceptance, plus every T29
follow-up listed on its row). Also closes the two T43 dependencies (per-user queue namespace,
flush after sign-in). Hands to T43b: add the `https://localhost` CORS origin and the deep-link
callback.

Not in PLAN.md: `FINPATH_CORS_ALLOWED_ORIGINS` and its startup validation; web environment files
(`fileReplacements`); a `POST /auth/logout` revoke endpoint (follow-up); the
`ngsw-config.json` `navigationUrls` exclusion for same-origin hosting (T79/T84); adopting the
legacy queue key.
