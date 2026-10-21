# FinPath

A web and Android personal-finance assistant that helps salaried Indians track every rupee, get out of debt, invest consistently and see a dated path to financial independence.

- Requirements (source of truth): [docs/BRD.md](docs/BRD.md)
- MVP build plan: [docs/PLAN.md](docs/PLAN.md)
- Docs index: [docs/README.md](docs/README.md)

## Packages

| Package | What | Stack | Lands in |
| --- | --- | --- | --- |
| `api/` | REST API and rules engine (all money maths) | Spring Boot 3, Java 21, Maven, PostgreSQL (Neon), Flyway, Google OAuth2/JWT | T02 |
| `web/` | Responsive PWA and Android app | Angular 18+ standalone components, ngx-echarts, Capacitor | T06 |
| `infra/` | Docker support; the local stack is `compose.yaml` at the repo root | Docker Compose | T03, T08 |

CI workflows live in `.github/workflows/` (T05), where GitHub Actions requires them.

## Commands

```sh
# api/
./mvnw test

# web/
npm test
ng build
```

## Running locally

```sh
cp .env.example .env          # placeholder dev values; .env is gitignored
docker compose up -d          # Postgres 17 on localhost:5433
cd api
set -a; . ../.env; set +a     # flyway-maven-plugin reads DB_* from the environment
./mvnw flyway:migrate flyway:info
./mvnw spring-boot:run        # local profile reads ../.env itself and migrates on start
```

The compose database listens on host port **5433**, not 5432, so it can sit beside a native PostgreSQL install. Change `DB_PORT` in `.env` to move it; compose, the Spring `local` profile and the Flyway plugin all read it. Settings: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` (see `.env.example`). `./mvnw test` needs no database.

If `./mvnw flyway:*` fails with an authentication error, `DB_PASSWORD` is almost certainly not exported in that shell (run the `set -a; . ../.env; set +a` line first).

## Production (Neon)

The `prod` profile reads one environment variable, `NEON_DATABASE_URL`: the connection string of the **pooled** endpoint from the Neon console (see `.env.example` for the expected shape). `NeonJdbcUrl` converts it to a JDBC URL plus separate user and password; none of it is stored in a tracked file. The app's HikariCP pool uses the pooled endpoint; Flyway migrations at startup use the **direct** endpoint, derived by dropping `-pooler` from the first host label. `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`, if set, replace all of this; also set `SPRING_FLYWAY_URL` (and user/password) to the **direct** endpoint, otherwise Flyway reuses the datasource URL, which may be the pooler. The prod profile does not read `.env`, so export `NEON_DATABASE_URL` in the shell, without surrounding quotes. A missing or malformed variable stops startup with a message that never echoes the value. `channel_binding` is translated to pgJDBC's `channelBinding`; other query parameters except `sslmode` are dropped.

Neon suspends idle compute, so the first connection can take seconds. The pool waits up to 30 s and does not log that as an error (tuning and reasons are in `application.yml`, prod section).

To run the Flyway Maven plugin against Neon's direct endpoint, export the pieces separately (never paste the string into a tracked file): `DB_HOST` (direct host, no `-pooler`), `DB_PORT=5432`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, and `DB_PARAMS='?sslmode=require'`. Then `./mvnw flyway:info`.

## Contributing

One feature = one branch = one FR id. See [CONTRIBUTING.md](CONTRIBUTING.md).
