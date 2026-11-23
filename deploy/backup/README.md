# Backup runner

The `backup` service in `compose.prod.yml`. Full setup is in [docs/hosting.md](../../docs/hosting.md#backups); this covers
the scripts, the off-site copy (deep review A1, 2026-11-09) and the encryption (2026-11-12).

Everything a backup writes is encrypted with [age](https://age-encryption.org) to the public key(s) in
`BACKUP_AGE_RECIPIENT`. The private key is only in Bitwarden ([key setup](../../docs/hosting.md#backup-encryption-key)), so
the server can write backups but never read them, and anything that decrypts runs on Zach's own machine.

| Script | Where | What it does |
|---|---|---|
| `schedule.sh` | server | The container's main process. Runs `backup.sh` daily at `BACKUP_AT` (UTC), then `backup-check.sh` if the backup succeeded. |
| `backup.sh` | server | Refuses to run without a valid `BACKUP_AGE_RECIPIENT`. `pg_dump`, encrypted, to `postgres/lookmate-<time>.dump.age` with a plain row count per table (`.counts`); each photo over an hour old not yet copied, encrypted, to `photos-age/<key>.age` (except clients' inspiration photos under `intake/`, which are never backed up: retention deletes them and this copy can't); then the same to the off-site remote when it's set; then prunes the copies of photos the app no longer keeps once they're `BACKUP_PHOTO_LOCK_DAYS` old (30), here and off-site, refusing (and failing) above `BACKUP_PRUNE_MAX` (100). |
| `backup-check.sh` | server | The newest dump is there, under 26 h old and starts with the age header; it has its `.counts`; every photo the app keeps over 2 h old has an encrypted copy; no deleted photo's copy is more than two days past its lock; and off-site holds the same. Reports plain-text backups left from before encryption. |
| `restore-test.sh` | your machine | Decrypts the newest dump (`AGE_IDENTITY`), restores it into a throwaway Postgres in the container, compares row counts with `.counts`, fails if a table has fewer rows or `org`, `app_user` or `flyway_schema_history` restored empty, and decrypts the five newest photos. |
| `decrypt-dump.sh` | your machine | Disaster recovery: decrypts the newest (or a named) dump to a mounted folder, for `scp` to the server. |
| `restore-photos.sh` | your machine | Disaster recovery: decrypts every encrypted photo missing from the photo bucket and uploads it, never overwriting. |

Pings, each `/start` then success or `/fail`: `BACKUP_HEALTHCHECK_URL` (daily), `BACKUP_CHECK_HEALTHCHECK_URL` (daily) and,
from your local `restore-test.env`, `RESTORE_TEST_HEALTHCHECK_URL` (monthly). The commands for the scripts that run on your
machine are in [docs/hosting.md](../../docs/hosting.md#restore-test). `AGE_IDENTITY` comes from the clipboard into one
PowerShell window's environment and goes to `age` through a pipe, so the private key is never written to a file.

**Photos are encrypted too,** one object per photo, rather than relying on the location strip alone: backup photos
don't expire by age, the bucket lock stops the plain `photos/` copy being overwritten with stripped versions, and it keeps "the server
can't read the backups" true for everything. A photo is copied once (by name) and never again, so one rewritten after
it was copied keeps its old copy; the app only rewrites originals at upload, and the one-off location backfill runs
before the first encrypted backup.

**Deleted photos leave the backup** (review 2026-11-22, #3). The stylist terms promise a deleted photo is gone within 30
days, backups included. Only photos the app keeps (`portfolio_photo.status` `READY` or `HIDDEN`) are copied, and each
night `backup.sh` deletes the copy of every photo no longer kept once it is `BACKUP_PHOTO_LOCK_DAYS` old by the
bucket's clock (it can't before: the lock rule refuses). More than `BACKUP_PRUNE_MAX` at once deletes nothing and fails
the run, so an empty or wrong photo list can't empty the backup: check what it would delete, then run it by hand with
a higher limit (`docker compose -f compose.prod.yml exec -e BACKUP_PRUNE_MAX=500 backup backup.sh`).
`restore-photos.sh` can still bring back a photo deleted in the last 30 days, as an orphan object the app ignores.

## Off-site copy

Off unless `OFFSITE_REMOTE` and `OFFSITE_PATH` are both set in `.env`. Setting only one fails the backup and pings
`/fail`, so a half-done setup doesn't pass for a working one. Any provider rclone supports works: `OFFSITE_REMOTE` is
an rclone [connection string](https://rclone.org/docs/#connection-strings) ending in `:`, and `OFFSITE_PATH` is the
bucket or folder under it. The scripts wrap it in an alias remote called `offsite:`, so log lines don't show the key.

The copy holds only encrypted files and uses `--immutable`: an object already there is never overwritten. The only
deletes are the nightly prune of deleted photos, as above, so the key needs delete permission on `photos-age/`, and
any lock, Object Lock or snapshot retention there must be no longer than `BACKUP_PHOTO_LOCK_DAYS`, or deleted photos
stay off-site past 30 days. Old dumps pile up, so give the provider its own expiry rule for `postgres/`. Use an account that shares nothing with Cloudflare (other email, other
payment card if you can), and keep its login in Bitwarden.

### Example: Backblaze B2

1. Create a B2 account, then a private bucket, e.g. `lookmate-offsite`. Turn on Object Lock if you want deletes
   blocked even for a leaked key, with a default retention no longer than `BACKUP_PHOTO_LOCK_DAYS` (e.g. 28 days).
2. Lifecycle settings: a custom rule that deletes `postgres/` files after e.g. 60 days, and "Keep prior versions"
   for at most 1 day, so a pruned photo's hidden version goes too.
3. Application Keys → Add a New Application Key: access to `lookmate-offsite` only, type Read and Write.
   It needs `deleteFiles` for the prune; Object Lock is what stops a leaked key deleting young backups.
4. In `.env`:

   ```
   OFFSITE_REMOTE=:b2,account=<keyID>,key=<applicationKey>:
   OFFSITE_PATH=lookmate-offsite
   ```

### Example: Hetzner Storage Box

1. Order a Storage Box (BX11 is plenty). In its settings turn on SSH support and automatic snapshots (e.g. daily,
   keep 14): the snapshots are what protect the copy from a leaked password, since SFTP can delete.
2. Create a sub-account limited to a folder, e.g. `lookmate`, and note its user (`uNNNNNN-subN`) and password.
3. Obscure the password on any machine with rclone: `rclone obscure '<password>'`.
4. In `.env`:

   ```
   OFFSITE_REMOTE=:sftp,host=uNNNNNN-subN.your-storagebox.de,user=uNNNNNN-subN,port=23,pass=<obscured>:
   OFFSITE_PATH=lookmate
   ```

   With a folder-limited sub-account the path is relative to that folder; any name works, e.g. `lookmate`.

## Trying it

After changing `.env`, recreate the service and run one backup and its check by hand:

```
docker compose -f compose.prod.yml --env-file .env up -d backup
docker compose -f compose.prod.yml run --rm backup bash -c "backup.sh && backup-check.sh"
```

Then run the restore test on your machine ([docs/hosting.md](../../docs/hosting.md#restore-test)). It needs free disk for
one more copy of the database (removed afterwards).
