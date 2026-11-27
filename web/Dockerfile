# Production image for the web app (docs/hosting.md): the built PWA served by Caddy, which also
# terminates HTTPS and proxies /api to the backend. Build context: web/. Caddyfile: deploy/Caddyfile, mounted.

FROM node:25-bookworm-slim AS build
WORKDIR /src
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
# Vite bakes these into the bundle at build time. None are secrets (see .env.example).
ARG VITE_AUTH0_DOMAIN
ARG VITE_AUTH0_CLIENT_ID
ARG VITE_AUTH0_AUDIENCE
RUN test -n "$VITE_AUTH0_DOMAIN" && test -n "$VITE_AUTH0_CLIENT_ID" && test -n "$VITE_AUTH0_AUDIENCE" \
    || { echo "Set VITE_AUTH0_DOMAIN, VITE_AUTH0_CLIENT_ID and VITE_AUTH0_AUDIENCE build args" >&2; exit 1; }
RUN npm run build

# Caddy rebuilt from source on the official builder (Go 1.27.4), with golang.org/x/net raised past the version 2.11.9
# ships (CVE-2026-78661). Drop the --replace once a Caddy release includes x/net 0.62.0 or later. The Images scan
# went red on 2026-11-23 for OpenSSL, curl, musl and Go stdlib CVEs in caddy:2.10.
FROM caddy:2.11.9-builder AS caddy
RUN xcaddy build v2.11.9 --replace golang.org/x/net=golang.org/x/net@v0.62.0 --output /caddy

FROM caddy:2.11.9
# Alpine's packages upgraded at build time, so fixes newer than the base image are in. The rebuilt binary needs the
# same capability the official image gives its own.
RUN apk upgrade --no-cache
COPY --from=caddy /caddy /usr/bin/caddy
RUN setcap cap_net_bind_service=+ep /usr/bin/caddy
RUN addgroup -S -g 10001 caddy \
    && adduser -S -D -H -u 10001 -G caddy -s /sbin/nologin caddy \
    && mkdir -p /data/caddy /config/caddy \
    && chown -R caddy:caddy /data /config
COPY --from=build /src/dist /srv
USER caddy
