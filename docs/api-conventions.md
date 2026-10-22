# API conventions

Applies to every endpoint in `api/`. Tested by `ErrorModelTest`, `OpenApiTest`, `LogHygieneTest` (T32).

## Errors
Every non-2xx JSON response has exactly this body and nothing else:

```json
{"status": 403, "error": "consent_required"}
```

`error` is a fixed snake_case code. There is no `message`, `detail`, `path`, field name, exception
class or stack trace, because those can echo amounts, merchant text, emails or tokens from the
request. Clients switch on `error`; the HTTP status is repeated in `status` for convenience.

| HTTP | `error` | When |
| --- | --- | --- |
| 400 | `invalid_request` | malformed JSON, wrong type (including a non-integer paise value), missing or bad parameter, consent statement mismatch |
| 400 | `invalid_redirect` and the other sign-in codes | sign-in start/return validation (T29) |
| 401 | `unauthorized` | no bearer token |
| 401 | `invalid_token` | bad, tampered, expired or deleted-account token |
| 401 | `invalid_grant` | one-time code or refresh token not accepted |
| 401 | `login_failed` | Google sign-in failed and no valid return URL exists |
| 403 | `forbidden` | authenticated but not allowed |
| 403 | `consent_required` | write attempted before consent (T31) |
| 404 | `not_found` | unknown route or resource, including another user's row (RLS makes it indistinguishable) |
| 405 | `method_not_allowed` | |
| 406 | `not_acceptable` | |
| 415 | `unsupported_media_type` | |
| 500 | `server_error` | anything unexpected |

Implementation: `com.finpath.api.ErrorCode` and `ApiError`; `GlobalExceptionHandler` maps Spring MVC
exceptions and is the 500 fallback (it re-throws `AccessDeniedException` and `AuthenticationException`
so security keeps owning 401/403). The security layer writes the same shape via `auth.JsonErrors`.
Feature handlers (for example `DpdpExceptionHandler`) take precedence and add feature codes. New
codes are added to `ErrorCode` or the feature class and to the table above; never built from input.
Unauthenticated 401s also carry a fixed `WWW-Authenticate` header.

A cold database (Neon wake-up) is slow, not an error: it must not surface as a 5xx or be logged as one.

## Money: integer paise
- Every money amount in a request or response is a JSON **integer** number of paise (1 INR = 100
  paise), backed by Java `long` and Postgres `bigint`. Field names end in `Paise` (or `_paise` in
  exports), for example `"amountPaise": 123450` means INR 1,234.50.
- Never a float, a decimal string, or a rupee value. Jackson is configured with
  `accept-float-as-int: false`, so `12.5` for a paise field is a 400 `invalid_request`, not a silent 12.
  The OpenAPI document must contain no `format: float|double` (tested).
- Percentages and rates are separate fields with their own documented unit; they are not money.
- INR formatting (symbol, lakh/crore grouping, rounding for display) is **UI-only**. The API never
  returns a formatted amount string.
- Calculations happen in the `rules` package with `BigDecimal` and are converted to paise at its edge.
- Values beyond 2^53 are not expected; clients may use `number`, but must treat amounts as integers.

## OpenAPI
`GET /v3/api-docs` (JSON, no credentials: it describes routes and schemas, never data). Swagger UI is
not shipped. Default off in the `prod` profile; set `OPENAPI_ENABLED=true` to serve it there. The
document declares the `bearer` JWT scheme.

## Identity and authorisation
The user is always the authenticated principal (`@CurrentUserId`); ids never come from a path, query
or body. Row-level security is the backstop (T30).

## Logging
- Log exception **classes** and counts, never exception messages, stack traces, request bodies, SQL
  parameters, amounts, merchant text, emails, names, bearer tokens, refresh tokens or one-time codes.
- `org.springframework.web` is pinned to INFO in `application.yml` because Spring logs
  `Resolved [<exception>: <message>]` and JSON parse errors (which can quote the input) at DEBUG.
- `LogHygieneTest` and `SignInLogHygieneTest` capture root-logger output at DEBUG across the sign-in
  failure path, token and refresh failures, bad bearers and a 500, and assert none of it matches an
  amount, email or token pattern or contains the planted secret values. New logging that handles user
  data must be added to those tests.
