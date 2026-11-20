# Local development

## One-time setup

- **JDK 25 and Node 24** (Node 22 works for the web app).
- **Docker** for Postgres (with pgvector) and MinIO.
- **Auth0:** a LookMate tenant, see [auth.md](auth.md). Copy `web/.env.example` to `web/.env.local` and fill it in.

## Run it

```bash
docker compose up -d                      # Postgres+pgvector on 5432, MinIO on 9000 (console 9001)
cd backend && SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI="https://<tenant>.us.auth0.com/" \
  SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES="https://api.lookmate.app" ./gradlew :app:bootRun
cd web && npm run dev                     # http://localhost:5173
```

On Windows use `.\gradlew.bat`, `npm.cmd`, and `$env:NAME="value";` for variables.

The backend applies the Flyway migrations (including `create extension vector`) and creates the photo bucket on
startup. Every default in `application.yml` points at the compose services; production uses the `prod` profile, which
reads everything from the environment ([hosting.md](hosting.md)).

## Tests

```bash
cd backend && ./gradlew test     # the Testcontainers tests skip without Docker; CI fails instead (LOOKMATE_REQUIRE_DOCKER)
cd web && npm test && npm run lint && npm run build
```

After changing an API: `./gradlew test -Dopenapi.update=true`, then `npm run api:types` in `web/` ([api-types.md](api-types.md)).

## Try the scaffold

1. Log in as your ADMIN user. `POST /api/admin/orgs` with `{"name":"…","type":"STYLIST"}`, then
   `POST /api/admin/orgs/{id}/invites` for a link (admin screens for this come with the onboarding work).
2. Open `/invite/<token>` in a private window, sign in as another user, accept. The home page shows the stylist role.
