# Combpatible

Product name: Combpatible (working name until 2026-11-20: LookMate). `lookmate` stays the internal name in packages,
Gradle, databases, buckets, config keys and Auth0 identifiers.

Matches hair clients with stylists by the stylist's portfolio work on similar hair. Pilot: Columbus, Ohio, with
Salon Lofts stylists.

- `backend/`: Spring Boot API (Java 25, Postgres + pgvector)
- `web/`: React PWA (Vite)
- `deploy/`: production Compose stack, Caddy, backups
- `docs/`:
  - [brief](docs/brief.md): what we build, why, the pilot metrics
  - [architecture](docs/architecture.md), [API types](docs/api-types.md), [auth](docs/auth.md),
    [data retention](docs/data-retention.md), [tracking](docs/tracking.md),
    [embedding providers](docs/embedding-providers.md)
  - modules: [stylists](docs/modules/stylists.md), [intake](docs/modules/intake.md), [matching](docs/modules/matching.md)
  - running it: [local dev](docs/local-dev.md), [hosting](docs/hosting.md)
  - design: [direction](docs/ux/design-direction.md), [redesign options](docs/ux/redesign-options.md) (Lookbook, decided)
  - research: [beyond the hair](docs/research/beyond-the-hair.md), [service finder](docs/research/service-finder.md)
  - [codebase review, 2026-11-22](docs/review-2026-11-22.md) and [orchestrator handoff](docs/orchestrator-handoff.md)

Start with [CLAUDE.md](CLAUDE.md).
