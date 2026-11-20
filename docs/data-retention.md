# Data retention

How long LookMate keeps personal data, and what takes it out. The mechanism is pokemans' (a daily job, settings under
`lookmate.retention` in `application.yml`); the periods are **defaults to confirm** with the team and the privacy policy.

| What | Kept | Then | Setting | Status |
|---|---|---|---|---|
| A client's inspiration photo (and any selfie, Phase 2) | 30 days after the request | Deleted from storage (everything under `intake/<request>/`, confirmed or not); `match_request.inspiration_deleted_at` set. The embedding and the matches stay: a vector can't be turned back into the photo. Never in the photo backups. | `inspiration-photo-days: 30` | Built (`intake/InspirationRetention`, daily with the email job) |
| An email's address, subject and bodies | 90 days after it was queued, once done with | Cleared; the row stays as the record | `email-content-days: 90` | Built (from pokemans) |
| A client's contact details | To decide: e.g. 12 months after their last request | Anonymised (`client.anonymised_at`) | – | To decide |
| Location and device metadata in uploaded photos | Not kept | Stripped at upload (pokemans' `ExifReader`/`PhotoService` approach). Inspiration photos: built (`intake/PhotoMetadata`; only the orientation is kept). Portfolio photos: with uploads | – | Inspiration: built. Portfolio: to build |
| A client's link (`match_request_link`) | 120 days after it was sent | Stops working (404); the hash stays | `lookmate.intake.link-ttl: 120d` | Built |
| A stylist's deleted photo | Gone from storage at once | Backups age out within 35 days | – | To build with portfolio |

## Biometric law (CLAUDE.md, Privacy)

Ohio has no biometric privacy law; Illinois (BIPA) and Texas (CUBI) do. Image embeddings of a face can count as
biometric identifiers there. Before LookMate takes clients from either state: written consent at upload, a published
retention schedule, and deleting embeddings of client photos with the photo. Designed for now: embeddings are in their
own table keyed by request, so deleting them with the photo is one statement.

## What the job can't reach

- **Backups.** Database dumps age out after 35 days. The photo copies in the backup bucket are kept and can't be
  deleted (bucket lock), so inspiration photos (`intake/`) are left out of the photo backup altogether
  (`deploy/backup/backup.sh`). The client's quiz answers and email are in the dumps, which age out after 35 days.
- **Emails.** The "we got your request" and "matches ready" emails carry the client's link; their bodies are cleared
  with the other email content after 90 days.
- **The embedding provider.** Check its terms: inputs must not be stored or used for training.
