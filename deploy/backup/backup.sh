#!/usr/bin/env bash
# One backup run (docs/hosting.md#backups). Everything it writes is encrypted with age to the public key(s) in
# BACKUP_AGE_RECIPIENT; the private key is only in Zach's Bitwarden, never on the server
# (docs/hosting.md#backup-encryption-key). Without a valid key it refuses to run and pings /fail.
#   1. pg_dump (custom format), checked with pg_restore --list, encrypted, to
#      backups:<bucket>/postgres/lookmate-<UTC timestamp>.dump.age, with a plain-text row count per table
#      (lookmate-<stamp>.counts: table names and numbers only) for the restore test to compare against
#   2. each portfolio photo the app still keeps (portfolio_photo.status READY or HIDDEN) not yet in
#      backups:<bucket>/photos-age/, encrypted one by one to <key>.age. Objects under an hour old wait for the next
#      night, so a photo is copied after its location data has been stripped at confirm (docs/data-retention.md),
#      not before. Abandoned uploads (PENDING_UPLOAD) keep their location data and are never copied
#   3. when OFFSITE_REMOTE and OFFSITE_PATH are set, the same encrypted dump, counts and photos to a second
#      provider in another account (deep review A1; deploy/backup/README.md)
#   4. prunes the encrypted copy of every photo the app no longer keeps (deleted, refused, abandoned), here and
#      off-site, once the bucket lock lets it (BACKUP_PHOTO_LOCK_DAYS, default 30): the stylist terms promise a
#      deleted photo is gone within 30 days, backups included (docs/data-retention.md)
# Pruning is the only delete. Old dumps go by the bucket's lifecycle rule, and its lock rule stops anyone deleting
# a backup early, whoever holds this token (docs/hosting.md#3-r2-buckets-and-tokens). A prune bigger than
# BACKUP_PRUNE_MAX (default 100) deletes nothing and fails the run, so a bad photo list can't empty the backup.
# Run by hand: docker compose -f compose.prod.yml exec backup backup.sh
set -euo pipefail
export LC_ALL=C   # one sort order for every list comm compares

: "${PHOTO_BUCKET:?}" "${BACKUP_BUCKET:?}"
lock_days="${BACKUP_PHOTO_LOCK_DAYS:-30}"
prune_max="${BACKUP_PRUNE_MAX:-100}"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

hc() { [[ -n "${HEALTHCHECK_URL:-}" ]] && curl -fsS -m 10 --retry 3 "${HEALTHCHECK_URL}$1" > /dev/null || true; }

if [[ ! "$lock_days" =~ ^[0-9]+$ || ! "$prune_max" =~ ^[0-9]+$ ]]; then
  echo "backup: BACKUP_PHOTO_LOCK_DAYS and BACKUP_PRUNE_MAX must be whole numbers" >&2
  hc /fail
  exit 1
fi

# shellcheck source=age-recipients.sh
if ! source "$(dirname "$0")/age-recipients.sh"; then
  hc /fail
  exit 1
fi

# The off-site copy is off unless both are set. Half a setting fails the run rather than quietly
# skipping the copy someone meant to turn on.
offsite=false
if [[ -n "${OFFSITE_REMOTE:-}" || -n "${OFFSITE_PATH:-}" ]]; then
  if [[ -z "${OFFSITE_REMOTE:-}" || -z "${OFFSITE_PATH:-}" || "${OFFSITE_REMOTE}" != *: ]]; then
    echo "backup: set both OFFSITE_REMOTE (an rclone remote ending in ':') and OFFSITE_PATH, or neither" >&2
    hc /fail
    exit 1
  fi
  # An alias remote, so log lines and errors name "offsite:" and not the connection string's key.
  export RCLONE_CONFIG_OFFSITE_TYPE=alias
  export RCLONE_CONFIG_OFFSITE_REMOTE="${OFFSITE_REMOTE}${OFFSITE_PATH}"
  offsite=true
fi

trap 'hc /fail; rm -rf "$work"' ERR
hc /start

dump="lookmate-${stamp}.dump.age"
counts="lookmate-${stamp}.counts"
echo "backup ${stamp}: dumping postgres"
# Counted just before the dump, so the restore test can compare. Rows written in between make the dump a
# little bigger than the counts, never smaller.
psql -tA -c "select string_agg(format('select %L || '' '' || count(*) from %I', table_name, table_name), ' union all ' order by table_name)
    from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE'" \
  | psql -tA > "$work/$counts"
pg_dump --format=custom --no-owner --file="$work/lookmate.dump"
pg_restore --list "$work/lookmate.dump" > /dev/null   # fails on a truncated or corrupt dump
age -e "${age_args[@]}" -o "$work/$dump" "$work/lookmate.dump"
size=$(du -h "$work/lookmate.dump" | cut -f1)
rm -f "$work/lookmate.dump"   # the plain dump never leaves this container
rclone copyto "$work/$dump" "backups:${BACKUP_BUCKET}/postgres/${dump}"
rclone copyto "$work/$counts" "backups:${BACKUP_BUCKET}/postgres/${counts}"

echo "backup ${stamp}: encrypting new photos"
# Only the photos the app keeps. Clients' inspiration photos (intake/) have no portfolio_photo row, so they are
# never copied: they are deleted after 30 days (docs/data-retention.md). A lost one costs the client a re-upload.
# Names, not sizes: an encrypted copy is a little bigger than its photo, and a photo is never rewritten once it's
# READY and an hour old (the location strip replaces the object at confirm, before the row turns READY).
psql -tA -c "select object_key from portfolio_photo where status in ('READY', 'HIDDEN')" | sort > "$work/kept.txt"
rclone lsf -R --files-only --min-age 1h --exclude 'intake/**' "photos:${PHOTO_BUCKET}" | sort > "$work/objects.txt"
comm -12 "$work/objects.txt" "$work/kept.txt" > "$work/photos.txt"
rclone lsf -R --files-only "backups:${BACKUP_BUCKET}/photos-age" | sed -n 's/\.age$//p' | sort > "$work/copied.txt"
comm -23 "$work/photos.txt" "$work/copied.txt" > "$work/new.txt"
new=$(wc -l < "$work/new.txt")
# In batches of 200, so the container never holds more than a batch of plain photos at once.
split -l 200 "$work/new.txt" "$work/batch."
for batch in "$work"/batch.*; do
  [[ -e "$batch" ]] || continue
  mkdir -p "$work/plain" "$work/sealed"
  rclone copy "photos:${PHOTO_BUCKET}" "$work/plain" --files-from-raw "$batch" --transfers 8 --stats-one-line --stats 0
  while IFS= read -r key; do
    mkdir -p "$(dirname "$work/sealed/$key")"
    age -e "${age_args[@]}" -o "$work/sealed/$key.age" "$work/plain/$key"
  done < "$batch"
  rm -rf "$work/plain"
  rclone copy "$work/sealed" "backups:${BACKUP_BUCKET}/photos-age" --transfers 8 --stats-one-line --stats 0
  rm -rf "$work/sealed"
done

if $offsite; then
  # --immutable: an object already there is never overwritten, so a bad run can't replace a good copy.
  echo "backup ${stamp}: copying dump and photos off-site"
  rclone copyto "$work/$dump" "offsite:postgres/${dump}" --immutable
  rclone copyto "$work/$counts" "offsite:postgres/${counts}" --immutable
  rclone copy "backups:${BACKUP_BUCKET}/photos-age" "offsite:photos-age" --immutable --transfers 4 --stats-one-line --stats 0
fi

# Prunes the encrypted copies under <remote>photos-age whose photo the app no longer keeps and that the lock lets
# go (uploaded at least lock_days ago, by the server's clock). Younger ones wait for a later night.
prune() {
  local remote=$1 name=$2 stale due
  rclone lsf -R --files-only "${remote}photos-age" | sed -n 's/\.age$//p' | sort > "$work/$name.copied"
  comm -23 "$work/$name.copied" "$work/kept.txt" > "$work/$name.stale"
  rclone lsf -R --files-only --min-age "${lock_days}d" --use-server-modtime "${remote}photos-age" \
    | sed -n 's/\.age$//p' | sort > "$work/$name.unlocked"
  comm -12 "$work/$name.stale" "$work/$name.unlocked" | sed 's/$/.age/' > "$work/$name.due"
  stale=$(wc -l < "$work/$name.stale")
  due=$(wc -l < "$work/$name.due")
  if (( due > prune_max )); then
    echo "backup ${stamp}: refusing to prune ${due} photos from ${name} (more than BACKUP_PRUNE_MAX=${prune_max});" \
      "check the list, then run backup.sh by hand with a higher BACKUP_PRUNE_MAX" >&2
    return 1
  fi
  if (( due > 0 )); then
    rclone delete "${remote}photos-age" --files-from-raw "$work/$name.due" --stats-one-line --stats 0
  fi
  echo "backup ${stamp}: ${name}: pruned ${due} deleted photos, $(( stale - due )) more wait for the ${lock_days}-day lock"
}

echo "backup ${stamp}: pruning photos the app no longer keeps"
prune "backups:${BACKUP_BUCKET}/" backup
if $offsite; then
  prune "offsite:" offsite
fi

hc ""
echo "backup ${stamp}: done (${size} dump, encrypted; ${new} new photos$($offsite && echo "; copied off-site"))"
