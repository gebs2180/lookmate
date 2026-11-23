#!/usr/bin/env bash
# Read-only health report. Run it from a checkout, without deploying it first:
#   ssh -o BatchMode=yes deploy@167.233.167.167 'bash -s' < deploy/prod-check.sh
# Add a window as the first argument, for example: 'bash -s -- 6h'
set -uo pipefail

since="${1:-1h}"
cd ~/lookmate/deploy || exit 1
compose="docker compose -f compose.prod.yml"

echo "== commit"
git -C ~/lookmate log -1 --format='%h %ad %s' --date=iso
echo "== services"
$compose ps --format 'table {{.Service}}\t{{.Status}}'
echo "== health"
curl -s -o /dev/null -w 'site %{http_code}\n' https://combpatible.app/
curl -s -w '\nhealth %{http_code}\n' https://combpatible.app/actuator/health
echo "== backend errors since $since"
logs="$($compose logs --since "$since" --no-log-prefix backend 2>&1)"
printf '%s\n' "$logs" | grep -c ' ERROR ' || true
printf '%s\n' "$logs" | grep ' ERROR ' | tail -5 | cut -c1-240

# Read-only queries. </dev/null keeps exec from eating the rest of this script on stdin.
q() { $compose exec -T postgres psql -U lookmate -d lookmate -X -q -c "begin read only;" -c "$1" -c "rollback;" </dev/null; }
# Counts, states and reasons only: no client email addresses or tokens are printed.
echo "== portfolio photos by status (and uploads never confirmed, over an hour old)"
q "select status, count(*),
          count(*) filter (where status = 'PENDING_UPLOAD' and created_at < now() - interval '1 hour') as stale_uploads
     from portfolio_photo group by status order by status;"
echo "== embeddings: ready photos with none yet, and failing attempts"
q "select count(*) as ready_without_embedding from portfolio_photo p
     where p.status = 'READY' and not exists (select 1 from portfolio_photo_embedding e where e.photo_id = p.id);"
q "select subject_type, model, count(*) as failing, max(attempts) as max_attempts, max(updated_at)::timestamp(0) as latest
     from embedding_attempt where last_error is not null group by subject_type, model order by 1, 2;"
q "select subject_type, attempts, updated_at::timestamp(0), left(last_error, 160) as last_error
     from embedding_attempt where last_error is not null order by updated_at desc limit 3;"
echo "== match requests by status (new in the last $since too)"
q "select status, count(*), count(*) filter (where created_at > now() - interval '$since') as recent
     from match_request group by status order by status;"
echo "== match requests stuck (NEW with a photo over 15 min, MATCHING over 15 min)"
q "select status, count(*), min(updated_at)::timestamp(0) as oldest from match_request
     where (status = 'NEW' and inspiration_object_key is not null and created_at < now() - interval '15 minutes')
        or (status = 'MATCHING' and updated_at < now() - interval '15 minutes')
     group by status;"
echo "== latest failed match requests"
q "select id, market, updated_at::timestamp(0), status_reason from match_request
     where status = 'FAILED' order by updated_at desc limit 5;"
echo "== emails: overdue, stuck sending, failed in the last $since"
q "select count(*) filter (where status = 'PENDING' and next_attempt_at < now() - interval '5 minutes') as overdue,
          count(*) filter (where status = 'SENDING' and updated_at < now() - interval '15 minutes') as stuck_sending,
          count(*) filter (where status = 'FAILED' and updated_at > now() - interval '$since') as failed_recently
     from email_message;"
q "select updated_at::timestamp(0), attempts, status_reason, left(last_error, 160) as last_error from email_message
     where status = 'FAILED' order by updated_at desc limit 3;"
echo "== inspiration photos past retention (30 days, checked at 31)"
q "select count(*) as overdue from match_request
     where inspiration_object_key is not null and inspiration_deleted_at is null
       and created_at < now() - interval '31 days';"
