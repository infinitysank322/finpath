# T29 — Google sign-in → FinPath JWT (as-built design)

Status: records the design as built on `infra/t29-google-oauth-jwt`, including the hardening going in
alongside this note (PKCE, client state echo, `V2_1` lookup function, required `exp`, clean
`WWW-Authenticate`, PII-free upsert failure). Code: `api/src/main/java/com/finpath/auth`,
`api/src/main/java/com/finpath/user`. Later tasks (T30, T31, T32, T33, T43b, T84) may rely on
everything below; changing a contract here means updating this note.

No rules (no money maths), no new tables, no screens in T29. `[?OQ-4]`: client id, redirect URIs,
return URLs and domain are env config only.

## 1. Flow

One Google OAuth client of type **Web application**. Its only authorised redirect URI is the API's
Spring default `{baseUrl}/login/oauth2/code/google` (locally `http://localhost:8080/...`; prod is the
public HTTPS host, with `{baseUrl}` correct only because forwarded headers are honoured from the
trusted proxy). The API is the confidential client; the web and Android apps never see a Google
token or the client secret.

```
client (web tab / Android Custom Tab)           API                          Google
1 make code_verifier (43-128 chars), state;
  code_challenge = BASE64URL(SHA256(verifier))
2 GET /oauth2/authorization/google
      ?redirect_uri=<allow-listed return URL>
      &code_challenge=<S256>&state=<client state>  ──►  guard: redirect_uri exactly one value,
                                                        on allow-list, challenge present → else 400
                                                        save {return URL, challenge, client state}
                                                        with Spring's auth request (session, ≤10 min)
                                                   ◄──  302 to Google (Google's own state + nonce)
3 user consents at Google                                                    ──► 302 {baseUrl}/login/oauth2/code/google
4                                                       Spring exchanges Google code, validates ID token
                                                        email_verified must be true
                                                        email.strip().toLowerCase(ROOT) → users.id
                                                        (find-or-create), session invalidated
                                                   ◄──  302 <return URL>?code=<one-time>&state=<client state>
                                                        (or ?error=email_not_verified|login_failed&state=…)
5 client checks state == its own, then
  POST /auth/token {code, codeVerifier}            ──►  consume code (single use), verify
                                                        SHA256(verifier) == stored challenge
                                                   ◄──  200 tokens  |  401 invalid_grant
6 Authorization: Bearer <access>  on every API call; POST /auth/refresh before/after expiry.
```

- **Web**: full-page navigation to step 2; return URL is the SPA route `…/auth/callback`
  (local default `http://localhost:4200/auth/callback`).
- **Android (T43b)**: step 2 opens in a **Chrome Custom Tab** (Google rejects embedded webviews,
  and a webview would expose the Google session to the app). Return URL is the deep link
  `com.finpath.app://auth/callback` (placeholder, OQ-4) handled by an intent filter. A custom scheme
  can be claimed by another app; PKCE is what makes an intercepted code useless, because the
  verifier never leaves the FinPath app.
- Return URLs are matched **exactly** against `FINPATH_AUTH_ALLOWED_REDIRECTS` (absolute URI, no
  fragment, no `*`, no normalisation; startup fails if empty or malformed). A bad return URL is a
  JSON 400 `invalid_redirect` and is never redirected to.
- `code_challenge` (base64url, exactly 43 chars) and `code_challenge_method=S256` are both required,
  each exactly once; a missing method or `plain` → 400 `invalid_request` (never defaulted).
  `state` is optional, at most once, 1–128 chars of `[A-Za-z0-9._~-]`, echoed verbatim on every
  return redirect. Challenge and state stay server-side and are never sent to Google.
- `/auth/token` removes the code before checking expiry and verifier (constant-time compare), so any
  attempt, right or wrong, burns it. `codeVerifier` must be 43–128 RFC 7636 characters.
- The one-time code, not a JWT, travels in the URL. The JWT never appears in a URL or log.

## 2. Endpoints and contracts

| Endpoint | Chain | Auth | Request | Success | Errors |
| --- | --- | --- | --- | --- | --- |
| `GET /oauth2/authorization/google` | login | public | query `redirect_uri`, `code_challenge`, `state` (client's, opaque, echoed verbatim) | 302 to Google | 400 `{"status":400,"error":"invalid_redirect"}`; missing/invalid PKCE → 400 |
| `GET /login/oauth2/code/google` | login | Google callback | Google's `code`, `state` | 302 `<return>?code=…&state=…` | 302 `<return>?error=login_failed\|email_not_verified&state=…`; unknown return URL → 400 / 401 JSON |
| `POST /auth/token` | api | public (code is the credential) | `{"code":"…","codeVerifier":"…"}` | 200 `TokenResponse` | 401 `{"status":401,"error":"invalid_grant"}` for every failure (unknown, used, expired, verifier mismatch) |
| `POST /auth/refresh` | api | public (refresh token is the credential) | `{"refreshToken":"…"}` | 200 `TokenResponse` (new refresh token) | 401 `invalid_grant` |
| `POST /auth/logout` | api | public (refresh token is the credential) | `{"refreshToken":"…"}` (body optional) | 204, `Cache-Control: no-store`, no body; revokes the presented token's whole family (T33b) | none: same 204 for unknown, expired or already-revoked tokens (idempotent, no oracle). Afterwards the token and any rotated successor get 401 `invalid_grant` on `/auth/refresh`; the user's other sessions are unaffected |
| everything else (`/me`, business APIs) | api | Bearer JWT | — | — | 401 `invalid_token`/`unauthorized`, 403 `forbidden` |

`TokenResponse = {"accessToken", "tokenType":"Bearer", "expiresIn":900, "refreshToken"}`, sent with
`Cache-Control: no-store` and `Pragma: no-cache`. Error bodies are fixed codes only
(`JsonErrors`), never messages. 401s carry a fixed `WWW-Authenticate: Bearer error="invalid_token"`
(or bare `Bearer`), never exception text.

## 3. Token model

| Artefact | Form | Lifetime | Storage (server) | Notes |
| --- | --- | --- | --- | --- |
| Access token | JWT, HS256, key `FINPATH_JWT_SECRET` (≥32 bytes) | 15 min | none (stateless) | claims `iss=finpath`, `sub=users.id` (UUID), `iat`, `exp`, `jti`. Decoder requires: HS256 only, valid signature, `exp` **present** and not past (30 s skew), `iss=finpath`, `sub` parses as UUID. No email, name or roles in claims. |
| Refresh token | opaque, 256-bit random, base64url | 30 d | SHA-256 hash only, in memory | rotated on every use; each sign-in = a new family; presenting an already-rotated token revokes the whole family (replay ⇒ assume theft ⇒ fresh Google sign-in). `revokeAll(userId)` exists, no endpoint yet. |
| One-time code | opaque, 256-bit random | ≤60 s (config caps it) | SHA-256 hash → {userId, challenge, expiry}, in memory | atomically removed on first presentation, so a failed verifier also burns it. |

Key rotation: changing `FINPATH_JWT_SECRET` invalidates live access tokens (≤15 min of 401s); clients
recover via refresh, which is independent of the key. Single key, no `kid`, by design for one instance.

## 4. Configuration (names only)

`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `FINPATH_JWT_SECRET`, `FINPATH_AUTH_ALLOWED_REDIRECTS`
(comma-separated). Startup fails if any is missing/weak. TTLs are `finpath.auth.access-token-ttl`,
`refresh-token-ttl`, `one-time-code-ttl` in `application.yml` (not secrets). Prod also depends on
`SERVER_FORWARD_HEADERS_STRATEGY` / trusted-proxy settings so `{baseUrl}` resolves to the public HTTPS
host, and on the secure session cookie. Values live in the untracked `.env` or host secret store
only; `.env.example` carries empty placeholders.

## 5. Filter chains

1. **Login chain** (`@Order(1)`), matcher `/oauth2/authorization/**`, `/login/oauth2/code/**`:
   `oauth2Login`, `ReturnUrlGuardFilter` before the redirect filter, request cache off, logout off,
   authorised client not stored (Google tokens are discarded). The only place an HTTP session exists
   (cookie `HttpOnly`, `SameSite=Lax` so the top-level Google callback carries it, `Secure` in prod,
   10 min timeout); invalidated in both success and failure handlers.
2. **API chain** (`@Order(2)`), everything else: stateless resource server (bearer JWT only, no
   cookies read, so CSRF off). Public: `/actuator/health/**`, `/auth/token`, `/auth/refresh`, `/auth/logout`, `/error`.
   Everything else authenticated. No CORS configured (see T33).

## 6. Identity and the users row

- Identity = Google's **verified** email, stripped and lower-cased; `users.email` is UNIQUE with a
  `lower()` check (V2). No Google `sub`, token or ID number is stored.
- `JdbcUserAccounts.findOrCreateByVerifiedEmail` in one transaction: look up the id via
  `V2_1__users_id_by_email.sql` — a `SECURITY DEFINER` function
  `finpath_user_id_by_email(text) RETURNS uuid` (SQL, STABLE, STRICT, `BEGIN ATOMIC`,
  `search_path = pg_catalog, pg_temp`) that returns only the id. Needed because V2 has
  `FORCE ROW LEVEL SECURITY` and the caller's id is not yet known.
  - FORCE RLS binds the table owner too, so the function is owned by a dedicated NOLOGIN role
    `finpath_signin` with column-level `SELECT (id, email)` and its own policy
    `users_signin_lookup … FOR SELECT TO finpath_signin USING (true)`. No other role gains read
    access; `app_user` is not a member of `finpath_signin`.
  - `EXECUTE` is revoked from `PUBLIC` and granted to `app_user` and the migrating role (the app's
    connection role until T30).
  - Proven by a DB test with a non-superuser, NOBYPASSRLS owner running the migrations: returning
    sign-in resolves to the same id as owner and as `app_user`, while a direct `SELECT` sees 0 rows.
  - The migrating role grants itself membership only for the ownership change, then revokes every
    membership it granted itself (including the extra one PG16+ adds under `createrole_self_grant`).
    V2_1 ends with a fail-closed check: unless superuser, it raises `V2_1 refused…` and rolls back if
    the migrating role can still use or `SET ROLE` to `finpath_signin` (possible only via a grant made
    by another role). Tested for `createrole_self_grant` unset, `inherit`, `set` and `inherit, set`.
  - `finpath_signin` is cluster-wide; on an existing cluster where it already exists without admin
    rights for the migrating role, V2_1 fails loudly.
  If absent: set `app.current_user_id` to a new UUID (satisfies `users_self` WITH CHECK) and
  `INSERT … ON CONFLICT (email) DO NOTHING RETURNING id`; on a lost race, look up again via the
  function.
- Any `DataAccessException` or `TransactionException` here → redirect `error=login_failed`; the log
  line carries the exception class only — no email, name, SQL text or exception message (tested).
- **CurrentUser seam**: `CurrentUser.id()` / `require()` read `sub` from the verified
  `JwtAuthenticationToken`; controllers take `@CurrentUserId UUID`. A user id never comes from a
  path, query or body. T30's connection initialiser calls `CurrentUser.id()` to set
  `app.current_user_id`.

## 7. Accepted risks and decisions

- **Email, not Google `sub`, as identity.** Simple, no extra column. Risk: a Google/Workspace address
  deleted and later reassigned to someone else would sign in to the old account. Migration option:
  add `google_sub` (opaque provider id, not a government/account number) captured on next sign-in,
  then match on sub; decision owned by T31.
- **In-memory refresh and code stores.** Restart signs everyone out (one-time codes cost a retry);
  works only for a single API instance, which matches T84's single VM. Persisting hashes is a later
  `[+TABLE]` task needing RLS, export and deletion like any table.
- **`users` row created at first sign-in, before consent.** It holds only name and email. Business
  tables must not be written before consent (T31), and T31 must purge or handle users who never
  consent.
- No logout endpoint in T29; the client discards tokens. Server-side sign-out-everywhere needs a
  route onto `revokeAll` (not in PLAN; suggest with T31's `DELETE /me` work or T33).

## 8. Follow-ups by task

- **T30**: switch the app to `app_user` and keep returning sign-in green; add
  `finpath_user_id_by_email` to the metadata test's allow-list of SECURITY DEFINER functions; `CrossTenantTest` proves the function
  returns only an id and nothing else of another user's row; initialiser reads `CurrentUser.id()`.
- **T31**: `DELETE /me` must `RefreshTokenStore.revokeAll`, revoke the user's live one-time codes
  (needs a `revokeAll` on `OneTimeCodeStore`) and make JWTs for a deleted user fail (a `sub` existence
  check, or a deleted-users deny set for ≤15 min); refresh must also refuse a deleted user. Consent
  gate on every business-table write. Purge/decline path for `users` rows with no consent. Decide
  `sub` vs email (section 7).
- **T32**: log-appender test also asserts no email-shaped strings (and no bearer/refresh/code values)
  in output, covering the sign-in failure path.
- **T33**: CORS for the web origin on the API chain, or a dev proxy (`/auth`, `/oauth2`, `/login`
  through the Angular dev server); generate PKCE verifier + state in the web client, keep the
  verifier in `sessionStorage` only for the round trip, check echoed state; token storage and
  refresh-on-401.
- **T43b**: Custom Tab (never a webview), deep link `com.finpath.app://auth/callback` added to
  `FINPATH_AUTH_ALLOWED_REDIRECTS`, verifier held in app memory/secure storage.
- **T84**: prod Google console redirect URI `https://<domain>/login/oauth2/code/google`; prod
  allow-list lists the hosted web callback and the app deep link; forwarded-header trust tightened
  to the proxy.

## 9. Decisions settled during the build

1. Parameter names: query `redirect_uri`, `code_challenge`, `code_challenge_method`, `state`;
   JSON `code`, `codeVerifier` (camelCase like `refreshToken`).
2. `state` is optional server-side (echoed if sent) and mandatory in our clients.
3. A PKCE failure at `/auth/token` is indistinguishable from other failures (`invalid_grant`) by
   design.

## Coverage

PLAN.md tasks: **T29** (fully). Informs T30, T31, T32, T33, T43b, T84; OQ-4 respected.
Not in PLAN.md: `V2_1__users_id_by_email.sql` (a function, not a table, so no `[+TABLE]` trio;
PLAN's migration list goes from V2 to V3 and does not mention it); PKCE + client state; a sign-out / revoke-all endpoint; one-time-code
`revokeAll`; deleted-user JWT rejection; optional future `google_sub` column; persistent token
stores as a future `[+TABLE]`.
