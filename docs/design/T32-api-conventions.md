# T32 - API conventions (as-built design)

Code: `api/src/main/java/com/finpath/api/{ErrorCode,ApiError,GlobalExceptionHandler,OpenApiConfig}.java`,
`auth/SecurityConfig`, `application.yml`, `pom.xml`. Reference doc: `docs/api-conventions.md`. No money maths.

## Decisions
1. **One error body** `{status, error}` with fixed codes. It matches what T29/T31 already emit, so no
   existing contract or test changed. No `message` field at all (a message is where leaks happen).
2. **GlobalExceptionHandler** extends `ResponseEntityExceptionHandler`, so all Spring MVC exceptions keep
   their right status (400/404/405/406/415) but lose ProblemDetail's echoed detail. The `Exception`
   catch-all returns 500 `server_error` and logs the class name only. It re-throws security exceptions so
   filters/entry points still answer 401/403. `DpdpExceptionHandler` is `HIGHEST_PRECEDENCE`, the global one
   `LOWEST_PRECEDENCE`, so feature codes win.
3. **springdoc-openapi-starter-webmvc-api 2.8.13**, exact pin (2.8.x is the Boot 3.4/3.5 line; released well
   over two weeks before this task). API module only, no Swagger UI, so no static assets or extra paths.
   `/v3/api-docs` is `permitAll` in the API chain (schemas only, no data); every other path stays
   authenticated (tested, including `/swagger-ui/**`). Disabled in `prod` unless `OPENAPI_ENABLED=true`,
   to keep the public surface small (tested against the YAML).
4. **Paise contract enforced, not just documented**: `spring.jackson.deserialization.accept-float-as-int=false`
   (Jackson otherwise truncates 12.5 to 12 for a `long`), and an OpenAPI test that fails on `float`/`double`.
5. **Log hygiene**: the leak found while writing the test was Spring's own DEBUG logging of
   `Resolved [exception: message]` and JSON parse errors. `logging.level.org.springframework.web: INFO` is set
   in the base config. `LogCapture` (test support) attaches a logback appender at root DEBUG, renders messages
   and stack traces, and offers email / amount / bearer-JWT detectors; the detectors are themselves tested
   so a pass is not vacuous. Scenarios: sign-in DB failure and provider failure with the email in the
   exception text (`SignInLogHygieneTest`, in the `auth` package because the handler collaborators are
   package-private), token and refresh failures (wrong verifier, replay, malformed JSON), tampered/garbage
   bearers, unknown route, and a 500 whose exception text holds an amount, merchant and email.

## Known limits
- Error codes owned by a feature are not enumerated in code, only in the doc table.
- Failures inside servlet filters before MVC (for example the security chain) use `auth.JsonErrors`, which
  writes the same shape by hand rather than sharing `ApiError`.
- A log line from a third-party library at WARN/ERROR that quotes user data would not be caught unless a
  scenario triggers it; the patterns are a net, not a proof.
