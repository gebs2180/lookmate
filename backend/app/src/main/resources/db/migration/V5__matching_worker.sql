-- The matching module's own bookkeeping (docs/modules/matching.md). V2 holds the embeddings and matches; this adds
-- what the embedding worker and the client's "matches are ready" link need.

-- An image the embedding worker failed on, so one unreadable photo backs off instead of being retried every pass
-- (and stops being retried after `lookmate.matching.worker.max-attempts`). A row is removed once the image embeds.
create table embedding_attempt (
    subject_type    text        not null check (subject_type in ('PORTFOLIO_PHOTO', 'MATCH_REQUEST')),
    subject_id      uuid        not null,
    model           text        not null,
    attempts        integer     not null,
    last_error      text,
    next_attempt_at timestamptz not null,
    updated_at      timestamptz not null,
    primary key (subject_type, subject_id, model)
);

-- The link emailed to a client when their matches are published. Only its SHA-256 is stored, like the intake link
-- (match_request.access_token_hash); either token opens the published matches. Interim: when intake offers a way to
-- issue a fresh client link, the email uses that and this table can go (docs/modules/matching.md, open questions).
create table match_link (
    id               uuid primary key,
    match_request_id uuid        not null references match_request (id),
    token_hash       text        not null unique,
    created_at       timestamptz not null
);

create index match_link_request_idx on match_link (match_request_id);

-- Whether the ranker found the stylist or the reviewer added them by hand, for the fairness report: a hair type whose
-- requests keep needing hand-added stylists is one the ranker serves poorly.
alter table match add column source text not null default 'SYSTEM' check (source in ('SYSTEM', 'ADMIN'));
