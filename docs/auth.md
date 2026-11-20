> **Combpatible uses its own Auth0 account and tenant** (not Rawmark's): a separate login page, branding, user list and
> post-login Action. Clients never sign in; only stylists and the admin do.
>
> The API audience is `https://api.combpatible.app` (Zach, 2026-11-20). The claim namespaces keep the internal name
> `lookmate` (see `CLAUDE.md`): `https://lookmate.app/roles` and `https://lookmate.app/mfa`.

# Auth setup

The backend is an OAuth2 resource server. The SPA logs in with a managed OIDC provider and calls the API with the access
token as a bearer token. The backend only validates tokens and reads ADMIN from them, so any OIDC provider works. The
steps below use Auth0's free tier.

## What the backend expects

| Setting | Environment variable | Local dev | Production |
|---|---|---|---|
| Issuer | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | `https://dev-xxxxxxxx.us.auth0.com/` | the production tenant's |
| Audience (API identifier) | `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_AUDIENCES` | `https://api.combpatible.app` | same |
| Roles claim | `LOOKMATE_SECURITY_ROLES_CLAIM` (optional) | default `https://lookmate.app/roles` | same |
| MFA claim | `LOOKMATE_SECURITY_MFA_CLAIM` (optional) | default `https://lookmate.app/mfa` | same |
| Require MFA for ADMIN | `LOOKMATE_SECURITY_REQUIRE_ADMIN_MFA` (optional) | **`false`** | default `true` |

The issuer's region (`us`) depends on where the tenant was created; copy the domain from the Auth0 dashboard and add
`https://` and the trailing slash.

- **ADMIN comes from the token.** The roles claim is a list of strings; only `ADMIN` (case-insensitive) is read from it.
- **STYLIST comes from the database.** An admin creates an org and an invite link; whoever accepts the link while signed
  in becomes a member of that org with the org's role. Nothing needs to be set per user in Auth0. Users are created in
  the database on their first API call, keyed by the token's `sub`.
- **MFA.** With `require-admin-mfa` on (the default, and production), ADMIN is granted only for an MFA login: the
  token's `amr` contains `mfa`, or the MFA claim is `true`. Auth0 puts `amr` only in the ID token, so the post-login
  Action sets the MFA claim on the access token. **Locally MFA is off** (below); production adds it back.
- With no issuer set, the app still starts, but every bearer token is rejected (fail closed). `/actuator/health` stays
  public.

| Path | Who |
|---|---|
| `/actuator/health`, `/actuator/info` | anyone |
| `/api/public/**` | anyone (the client flow, rate-limited; a client's own data needs their link token) |
| `/api/admin/**` | ADMIN |
| `/api/orgs/{orgId}/**` | members of that org, and ADMIN (checked per request; others get 404) |
| other `/api/**` (`/api/me`, `/api/invites/...`) | any signed-in user |
| everything else | denied (opened for the SPA's static files when the app serves them) |

`GET /api/me` returns the caller's subject, issuer, roles (ADMIN plus membership roles), org memberships, the raw roles
claim and whether the token shows MFA. The web app shows these after login: the quickest way to check the wiring.

## Local development: your dev tenant

Auth0's free plan allows one tenant per account, and Rawmark uses the other account's. So Combpatible has **its own
Auth0 account** (sign up with a different email or login method), with one dev tenant.

1. **Create the tenant.** Sign up at auth0.com with the new account (region US). New accounts can't choose the tenant
   name, so it gets Auth0's generated one: your dev tenant's domain (e.g. `dev-xxxx.us.auth0.com`; Friendly Name:
   Combpatible, under Settings). The issuer is `https://<that domain>/`, trailing slash included. The commands and
   examples here write it as `dev-xxxxxxxx.us.auth0.com`; use yours. It isn't a secret, but it stays out of the repo.
2. **Applications → APIs → Create API.**
   - Name: **Combpatible API** (cosmetic, can be renamed).
   - Identifier: **`https://api.combpatible.app`**. This is the audience; it can't be changed later, and the backend and
     `web/.env.local` use it as is.
   - Signing algorithm: RS256.
   - Then **Settings → Access Settings:** turn on **Allow Offline Access** (refresh tokens). Save.
3. **Applications → Applications → Create Application → Single Page Web Applications**, name **Combpatible Web**.
   In **Settings**:
   - Allowed Callback URLs, Allowed Logout URLs and Allowed Web Origins: `http://localhost:5173`.
   - **Refresh Token Rotation:** turn on **Allow Refresh Token Rotation**. Save.
   - Copy the **Domain** and **Client ID** for `web/.env.local`.
4. **Authorize the SPA on the API.** Applications → APIs → Combpatible API → **Application Access** (or "Machine to
   Machine / User Access" on older dashboards): set Combpatible Web to **Authorized** for user access. Newer tenants
   block apps from APIs by default, and the login then fails with "Client … is not authorized to access resource
   server".
5. **User Management → Roles → Create Role** `ADMIN`. Sign up once through the app (step 8 below) so your user exists,
   then User Management → Users → you → Roles → Assign `ADMIN`. Stylists join through invite links instead.
6. **Actions → Library → Create Action → Build from scratch**, trigger **Login / Post Login**, name
   `Combpatible claims`. Paste this, **Deploy**, then **Actions → Triggers → post-login**: drag it into the flow and
   **Apply**. It must be the only Action that sets these claims.
   ```js
   // Local development (dev tenant): no MFA. The backend runs with LOOKMATE_SECURITY_REQUIRE_ADMIN_MFA=false.
   exports.onExecutePostLogin = async (event, api) => {
     const roles = event.authorization?.roles ?? [];
     api.accessToken.setCustomClaim('https://lookmate.app/roles', roles);
     // Notifications go to this email. Verified only.
     if (event.user.email && event.user.email_verified) {
       api.accessToken.setCustomClaim('email', event.user.email);
     }
     api.accessToken.setCustomClaim('https://lookmate.app/mfa', false);
   };
   ```
   The claim names are the internal identifiers and stay `https://lookmate.app/...`. The Action doesn't call
   `api.multifactor.enable`, so no factor needs to be turned on under Security → Multi-factor Auth.
7. **Copy `web/.env.example` to `web/.env.local`** and fill it in:
   ```
   VITE_AUTH0_DOMAIN=dev-xxxxxxxx.us.auth0.com
   VITE_AUTH0_CLIENT_ID=<Combpatible Web's client ID>
   VITE_AUTH0_AUDIENCE=https://api.combpatible.app
   ```
   None of these are secrets.
8. **Run it** with the issuer, the audience and MFA off: the commands are in [local-dev.md](local-dev.md#run-it).
   Then log in at `http://localhost:5173` ("Stylist log in"), sign up, assign yourself `ADMIN` (step 5), **log out and
   back in** for a token with the role, and run the [smoke test](local-dev.md#smoke-test).

Changes to Actions can take a minute to take effect. Log out and back in for a fresh token: a refreshed token keeps
the claims it was first issued with until the next real login.

## Production (to do before the pilot)

A production tenant, in the same Combpatible Auth0 account once the plan allows a second tenant, or a paid plan: the
plan is **a decision pending**. Same API identifier and claim names, so the backend config doesn't change apart from
the issuer. Production differs from local in three places:

- Callback, logout and web-origin URLs: `https://pilot.combpatible.example` (the placeholder until a domain is bought).
- MFA is back on: `LOOKMATE_SECURITY_REQUIRE_ADMIN_MFA` unset (default `true`), **Security → Multi-factor Auth** with
  One-time Password enabled, and the Action requires MFA for admins:
  ```js
  exports.onExecutePostLogin = async (event, api) => {
    const roles = event.authorization?.roles ?? [];
    api.accessToken.setCustomClaim('https://lookmate.app/roles', roles);
    if (event.user.email && event.user.email_verified) {
      api.accessToken.setCustomClaim('email', event.user.email);
    }
    if (roles.includes('ADMIN')) {
      // Admins must pass MFA on every login; no token is issued unless they do.
      api.multifactor.enable('any', { allowRememberBrowser: false });
      api.accessToken.setCustomClaim('https://lookmate.app/mfa', true);
    } else {
      const mfa = (event.authentication?.methods ?? []).some((m) => m.name === 'mfa');
      api.accessToken.setCustomClaim('https://lookmate.app/mfa', mfa);
    }
  };
  ```
  Why the Action requires MFA itself: post-login Actions run *before* a policy-triggered MFA prompt, so checking
  `event.authentication.methods` alone misses the MFA done in the same login.
- Branding (Branding → Universal Login): the Combpatible name, ring mark and colours from
  [ux/design-direction.md](ux/design-direction.md).

## Web app

The SPA uses `@auth0/auth0-react` (`web/src/AuthProvider.tsx`) with the audience from `VITE_AUTH0_AUDIENCE`. After
login it calls `/api/me` and shows the roles the backend sees.

Logins survive a page reload through rotating refresh tokens cached in `localStorage` (`useRefreshTokens`,
`cacheLocation="localstorage"`). That's why the API needs **Allow Offline Access** and the SPA **Allow Refresh Token
Rotation** (steps 2 and 3). `localStorage` is readable by any script on the page, so this relies on rotation, short
token lifetimes and keeping third-party scripts off the app.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| Login page says "Client … is not authorized to access resource server" (or `access_denied`) | The SPA isn't authorized on the API | Step 4: Combpatible API → Application Access → authorize Combpatible Web |
| "Service not found: https://api.combpatible.app" | The API identifier differs from `VITE_AUTH0_AUDIENCE` | Make both exactly `https://api.combpatible.app` (no trailing slash); restart `npm run dev` after editing `.env.local` |
| "Callback URL mismatch" | `http://localhost:5173` missing from Allowed Callback URLs | Step 3; check the port Vite printed |
| Logged out on every reload, or "Missing Refresh Token" in the console | Offline access or rotation is off | Step 2 (Allow Offline Access) and step 3 (Refresh Token Rotation), then log out and in |
| Every API call is 401 | Issuer or audience wrong on the backend | Issuer `https://dev-xxxxxxxx.us.auth0.com/` **with** the trailing slash; audience `https://api.combpatible.app`. |
| Logged in, but `/admin/...` is 403 or the admin links don't show | No ADMIN in the token, or MFA required | `/api/me` shows the roles claim: empty means assign `ADMIN` (step 5) and check the Action is deployed **and** in the post-login flow, then log out and in. Roles there but still 403: the backend wasn't started with `LOOKMATE_SECURITY_REQUIRE_ADMIN_MFA=false` |
| An MFA enrolment prompt appears locally | The production Action (with `api.multifactor.enable`) or an MFA policy is on in the dev tenant | Use the local Action above; Security → Multi-factor Auth → Require Multi-factor Auth: Never |
| `/api/me` has no `email` | The email isn't verified | Verify it (Auth0 sends a link at sign-up), or mark it verified under User Management → Users |
| Can't create a second tenant | Free plan: one tenant per account | Use the separate Combpatible Auth0 account, not Rawmark's |
