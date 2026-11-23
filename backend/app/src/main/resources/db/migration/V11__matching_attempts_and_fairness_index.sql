-- Matching fixes from the 2026-11-22 review (docs/review-2026-11-22.md, group C).
-- V11, not the V9 reserved for this group: V10 (group A) is merged, and Flyway refuses a lower version applied after it.

-- #23, and retakes (group A): a request's tries, for the photo they were made on. Embedding and ranking each count
-- up to `lookmate.matching.worker.max-attempts`, then the request becomes FAILED. A retake (a new photo, so a new
-- SHA-256) starts both counts again. Pacing between tries is intake's `matching-stuck-after`, not a timestamp here.
create table match_request_attempt (
    match_request_id   uuid        primary key references match_request (id) on delete cascade,
    inspiration_sha256 text,
    embed_attempts     integer     not null default 0,
    rank_attempts      integer     not null default 0,
    last_error         text,
    updated_at         timestamptz not null
);

-- Requests' tries move to the table above; embedding_attempt keeps portfolio photos only (its next_attempt_at was
-- written for requests but never read).
insert into match_request_attempt (match_request_id, inspiration_sha256, embed_attempts, last_error, updated_at)
select a.subject_id, r.inspiration_sha256, a.attempts, a.last_error, a.updated_at
from embedding_attempt a join match_request r on r.id = a.subject_id
where a.subject_type = 'MATCH_REQUEST'
on conflict (match_request_id) do nothing;
delete from embedding_attempt where subject_type = 'MATCH_REQUEST';
alter table embedding_attempt drop constraint embedding_attempt_subject_type_check;
alter table embedding_attempt add constraint embedding_attempt_subject_type_check check (subject_type = 'PORTFOLIO_PHOTO');

-- Which photo an inspiration embedding was made from: after a retake the old vector is stale and is replaced.
-- A null never matches, so the photo is embedded again. Finished requests take their current photo's hash; requests
-- still waiting to be matched (perhaps after a retake) are left null and re-embedded.
alter table match_request_embedding add column inspiration_sha256 text;
update match_request_embedding e set inspiration_sha256 = r.inspiration_sha256 from match_request r
where r.id = e.match_request_id and r.status not in ('NEW', 'MATCHING');

-- #26: the client-facing reason on a stylist the admin added by hand (Zach, 2026-11-22). The ADMIN source stays.
update match set reason = 'Picked for you by the Combpatible team' where source = 'ADMIN' and reason = 'Added by a reviewer';

-- #27: the fairness report filters requests by market and date.
create index match_request_market_created_idx on match_request (market, created_at);
