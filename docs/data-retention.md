# Data retention

How long Combpatible keeps personal data, and what takes it out. The mechanism is pokemans' (a daily job, settings under
`lookmate.retention` in `application.yml`); the periods are **defaults to confirm** with the team and the privacy policy.

| What | Kept | Then | Setting | Status |
|---|---|---|---|---|
| A client's inspiration photo (and any selfie, Phase 2) | 30 days after the request | Deleted from storage (everything under `intake/<request>/`, confirmed or not); `match_request.inspiration_deleted_at` set. The embedding and the matches stay: a vector can't be turned back into the photo. Never in the photo backups. | `inspiration-photo-days: 30` | Built (`intake/InspirationRetention`, daily with the email job) |
| An email's address, subject and bodies | 90 days after it was queued, once done with | Cleared; the row stays as the record | `email-content-days: 90` | Built (from pokemans) |
| A client's contact details (email, phone, first name, neighborhood, free-text note) | 365 days after the latest request made with that email address (the request itself when no email was given) | Cleared and `client.anonymised_at` set; the quiz codes (hair type, texture, length, budget, past-issue codes) and market stay, so matching quality by hair type can still be measured | `client-contact-days: 365` | Built (`intake/ClientContactRetention`). Decided 2026-11-20 |
| Location and device metadata in uploaded photos | Not kept | Stripped at upload by `storage/PhotoMetadata` (the one copy; ported from pokemans' `ExactCopy.of`). Only the orientation is kept | – | Built for inspiration photos; portfolio photos call the same class |
| Portfolio uploads never confirmed | Deleted | Stripping happens at confirm, so an abandoned upload still has its metadata. `PendingUploadSweep` (hourly) marks a `PENDING_UPLOAD` photo older than the upload link's lifetime plus 1 hour `DELETED` and deletes its object | 1 hour after the upload link expires | Review 2026-11-22, finding 2 |
| A client's link (`match_request_link`) | 90 days after it was sent | Stops working (404); the hash stays | `lookmate.intake.link-ttl: 90d` | Built |
| A stylist's deleted photo | Gone from storage at once | Its encrypted backup copy is pruned by the nightly backup once the backup bucket's lock lets it go (`BACKUP_PHOTO_LOCK_DAYS`, 30 by default; 28 with the prefix lock rules in [hosting.md](hosting.md#3-r2-buckets-and-tokens)), here and off-site. The stylist terms promise 30 days, backups included | `BACKUP_PHOTO_LOCK_DAYS` | Built (`deploy/backup/backup.sh`, review 2026-11-22 #3) |

## Biometric law (CLAUDE.md, Non-negotiables)

Ohio has no biometric privacy law; Illinois (BIPA) and Texas (CUBI) do. Image embeddings of a face can count as
biometric identifiers there. Before Combpatible takes clients from either state: written consent at upload, a published
retention schedule, and deleting embeddings of client photos with the photo. Designed for now: embeddings are in their
own table keyed by request, so deleting them with the photo is one statement.

## What the job can't reach

- **Backups.** Database dumps age out after 35 days. Only the portfolio photos the app keeps (`READY` or `HIDDEN`)
  are copied to the backup bucket, encrypted; each night the backup prunes the copies of photos it no longer keeps
  (deleted, refused, abandoned) once the bucket lock allows, so a deleted photo leaves the backups within the 30 days
  the stylist terms promise. Inspiration photos (`intake/`) are never copied (`deploy/backup/backup.sh`). The
  client's quiz answers and email are in the dumps, which age out after 35 days.
  With the whole-bucket 30-day lock the worst case is about 31 days (deleted just after a night's prune of a copy
  exactly 30 days old); the prefix rules in [hosting.md](hosting.md#3-r2-buckets-and-tokens) with
  `BACKUP_PHOTO_LOCK_DAYS=28` bring it inside 30.
- **Emails.** The link, "matches ready" and "picks added" emails (sent only to clients who gave an email) carry the client's link; their bodies are cleared
  with the other email content after 90 days.
- **The embedding provider.** Check its terms: inputs must not be stored or used for training.
