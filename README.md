# Combpatible

Product name: Combpatible (working name until 2026-11-20: LookMate). `lookmate` stays the internal name in packages,
Gradle, databases, buckets, config keys and Auth0 identifiers.

Matches hair clients with stylists by the stylist's portfolio work on similar hair. Pilot: Columbus, Ohio, with
Salon Lofts stylists.

- `backend/`: Spring Boot API (Java 25, Postgres + pgvector)
- `web/`: React PWA (Vite)
- `deploy/`: production Compose stack, Caddy, backups
- `docs/`: [brief](docs/brief.md), [architecture](docs/architecture.md), [local dev](docs/local-dev.md), [hosting](docs/hosting.md)

Start with [CLAUDE.md](CLAUDE.md).
