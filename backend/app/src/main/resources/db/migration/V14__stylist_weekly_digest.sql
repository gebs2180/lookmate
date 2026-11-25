-- The weekly stylist digest (docs/tracking.md, "Weekly digest"; idea 2 of docs/research/feature-ideas.md).

-- One row per live stylist org per reported week: the queue the digest worker takes rows from with
-- FOR UPDATE SKIP LOCKED (docs/architecture.md), and the guarantee that an org gets one digest a week.
-- week_start is the Monday (tracking time zone) the reported week began.
create table stylist_digest (
    id          uuid        primary key,
    org_id      uuid        not null references org (id),
    week_start  date        not null,
    status      text        not null check (status in ('PENDING', 'DONE', 'FAILED')),
    attempts    integer     not null default 0,
    recipients  integer,                    -- emails queued, once DONE
    last_error  text,
    created_at  timestamptz not null,
    updated_at  timestamptz not null,
    unique (org_id, week_start)
);

create index stylist_digest_pending on stylist_digest (created_at) where status = 'PENDING';

-- A stylist (one member of one org) who turned the digest off, on their settings or through the email's unsubscribe
-- link. No row means it's on.
create table stylist_digest_opt_out (
    org_id     uuid        not null references org (id),
    user_id    uuid        not null references app_user (id),
    source     text        not null check (source in ('SETTINGS', 'UNSUBSCRIBE_LINK')),
    created_at timestamptz not null,
    primary key (org_id, user_id)
);

-- The links in a digest email. Only the SHA-256 of the token is stored. A RECORD link names one client's request
-- (and the match, or null for a directory pick) and is single-use and short-lived; it records nothing by itself: the
-- stylist signs in and confirms (a forwarded email can't record events). An UNSUBSCRIBE link turns the digest off for
-- its user and org without a sign-in. Rows are deleted 30 days after they expire.
create table stylist_digest_link (
    id               uuid        primary key,
    token_hash       text        not null unique,
    kind             text        not null check (kind in ('RECORD', 'UNSUBSCRIBE')),
    digest_id        uuid        not null references stylist_digest (id),
    org_id           uuid        not null references org (id),
    user_id          uuid        not null references app_user (id),
    match_request_id uuid        references match_request (id),
    match_id         uuid        references match (id),
    created_at       timestamptz not null,
    expires_at       timestamptz not null,
    used_at          timestamptz,
    used_by          uuid        references app_user (id),
    event_id         uuid,                  -- the match_event the link recorded
    check (kind = 'UNSUBSCRIBE' or match_request_id is not null)
);

create index stylist_digest_link_expires on stylist_digest_link (expires_at);
