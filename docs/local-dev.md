# Local development

Combpatible runs locally against Docker (Postgres with pgvector, MinIO) and a development Auth0 tenant, `lookmate-dev`,
with MFA off. Internal names (the database, buckets, the `LOOKMATE_*` variables, Auth0 identifiers) keep `lookmate`.

## One-time setup

- **JDK 25 and Node 24** (Node 22 works for the web app).
- **Docker** (Docker Desktop on Windows and macOS) for Postgres and MinIO.
- **Auth0:** the `lookmate-dev` tenant in Combpatible's own Auth0 account, set up as in [auth.md](auth.md#local-development-the-lookmate-dev-tenant):
  the **Combpatible API** (identifier `https://api.lookmate.app`, offline access on), the **Combpatible Web** SPA
  (`http://localhost:5173`, refresh-token rotation on) authorized on the API, the `ADMIN` role, and the post-login
  Action without MFA.
- Copy `web/.env.example` to `web/.env.local` and fill in the domain and client ID; then `cd web && npm ci`.

## Run it

Three terminals, from the repo root. The backend needs the issuer and audience, and MFA off for ADMIN.

**macOS / Linux**

```bash
docker compose up -d                      # Postgres+pgvector on 5432, MinIO on 9000 (console 9001)

cd backend
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI="https://lookmate-dev.us.auth0.com/" \
SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES="https://api.lookmate.app" \
LOOKMATE_SECURITY_REQUIRE_ADMIN_MFA=false \
./gradlew :app:bootRun                    # http://localhost:8080

cd web && npm run dev                     # http://localhost:5173, proxies /api to :8080
```

**Windows (PowerShell)**

```powershell
docker compose up -d

cd backend
$env:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI = "https://lookmate-dev.us.auth0.com/"
$env:SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES = "https://api.lookmate.app"
$env:LOOKMATE_SECURITY_REQUIRE_ADMIN_MFA = "false"
.\gradlew.bat :app:bootRun

cd web; npm.cmd run dev
```

`$env:` variables last for that PowerShell window only; set them again in a new one.

The backend applies the Flyway migrations (including `create extension vector`) and creates the photo bucket on
startup. Every other default in `application.yml` points at the compose services. Locally, matching uses the fake
embedding provider and emails are off (`NOTIFY_EMAIL_ENABLED` unset), so a client's results appear in place on `/find`
and no email is sent. Production uses the `prod` profile, which reads everything from the environment, and MFA is on
there ([hosting.md](hosting.md), [auth.md](auth.md#production-to-do-before-the-pilot)).

## Smoke test

After a fresh setup, or a change to auth, matching or the client flow. Log in as your ADMIN user first ("Stylist log
in"; after login the app shows the roles the backend sees, from `/api/me`: ADMIN should be there).

1. **`/admin/locations`:** add one salon location (any Salon Lofts address). Demo stylists need one to go live.
2. **`/admin/demo`:** choose real photos named as in `web/src/admin/demoStylists.ts` (e.g. `maya-1.jpg` to `nia-5.jpg`;
   only photos you have the rights to), then create the demo stylists. They go live, marked as demo, with booking
   links to example.com. Without photos they get drawn placeholders, which are fine for the screens but not for step 3.
3. **`/find`** in a private window (clients don't sign in): answer the quiz with the service and hair type of one demo
   photo (its tags are in `demoStylists.ts`) and upload **that same file**. The fake matcher only matches identical
   images: it seeds a vector from the photo's SHA-256, so any other photo scores close to 0 and finds nothing close.
4. **Results in place:** the page waits a few seconds, then shows the ranked list with that demo stylist first and a
   reason under each pick. No email is needed locally.
5. **See all stylists:** opens the directory of every live stylist, filterable by service and hair type.
6. **Book:** opens the stylist's booking link (example.com) with the UTM tags, and records the click.
7. **`/admin/matches`** (back in the admin window): the request is there with its published list and the Book click.

If a step fails, see [auth.md's troubleshooting table](auth.md#troubleshooting) for login problems and the table below
for the rest.

| Symptom | Cause | Fix |
|---|---|---|
| Backend won't start: connection refused on 5432 | Compose isn't running | `docker compose up -d`; `docker compose ps` should show both healthy |
| Every API call is 401 | Issuer or audience not set in this terminal | Set the variables again (Windows: in the same PowerShell window) and restart `bootRun` |
| `/admin/...` is 403 with ADMIN in `/api/me` | MFA still required | Start the backend with `LOOKMATE_SECURITY_REQUIRE_ADMIN_MFA=false` |
| `/admin/demo` won't create stylists | No active location | Step 1 |
| `/find` finds no close match | Different photo, or the quiz doesn't match its tags | Upload the exact demo file, with its service and hair type |
| Uploads fail in the browser | MinIO isn't up, or the bucket wasn't created | `docker compose ps`; restart the backend, which creates the bucket |
| Web shows "Couldn't reach Combpatible" | The backend isn't on :8080 | Start it, or set `API_PROXY` for `npm run dev` |

## Tests

```bash
cd backend && ./gradlew test     # the Testcontainers tests skip without Docker; CI fails instead (LOOKMATE_REQUIRE_DOCKER)
cd web && npm test && npm run lint && npm run build
```

On Windows: `.\gradlew.bat test`, and `npm.cmd` for npm.

After changing an API: `./gradlew test -Dopenapi.update=true`, then `npm run api:types` in `web/` ([api-types.md](api-types.md)).

## Invites

To try the stylist side: as ADMIN, create a stylist org and an invite link on `/admin/stylists`, open `/invite/<token>`
in a private window, sign up as another user and accept. That user's home page shows the stylist role, and the stylist
screens (profile, portfolio, terms) follow from there.
