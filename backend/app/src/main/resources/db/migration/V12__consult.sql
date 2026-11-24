-- The service finder, "Not sure what to book?" (docs/modules/consult.md, docs/research/service-finder.md).

-- One row per published version of a ruleset. The document is the whole validated ruleset JSON (questions, outcomes,
-- rules, tests), kept as one unit rather than spread over tables: a version is checked, published and served whole,
-- and never changes once published (the trigger below). A new upload is a new version.
create table consult_ruleset (
    id           uuid        primary key,
    ruleset_key  text        not null,              -- 'default' for the pilot: the partner's ruleset, both links
    version      integer     not null check (version > 0),
    credit       text,                              -- who wrote the rules, shown to clients; null for our example
    document     jsonb       not null,
    source       text        not null check (source in ('SEED', 'UPLOAD')),
    published_at timestamptz not null,
    published_by uuid,                              -- the admin who uploaded it (no foreign key: rows never change)
    unique (ruleset_key, version)
);

create function consult_ruleset_immutable() returns trigger language plpgsql as $$
begin
    raise exception 'consult_ruleset versions are never changed: upload a new version';
end $$;

create trigger consult_ruleset_no_update before update on consult_ruleset
    for each row execute function consult_ruleset_immutable();

-- One client's run through the finder. No client identity: the id (random) is the only handle, returned to the page
-- that made it. answers holds stored questions only; the colour-reaction answer only routes and is never kept.
-- Deleted after lookmate.retention.client-contact-days (ConsultRetentionJob).
create table consult_session (
    id                 uuid        primary key,
    ruleset_id         uuid        not null references consult_ruleset (id),
    ruleset_version    integer     not null,
    market             text        not null,
    channel            text        not null check (channel in ('GENERAL', 'PARTNER')),
    answers            jsonb       not null,
    outcome_id         text        not null,
    rule_index         integer     not null,
    created_at         timestamptz not null,
    book_clicks        integer     not null default 0,
    booked_at          timestamptz,
    share_with_stylist boolean     not null default false,
    find_stylist_at    timestamptz
);

create index consult_session_created_idx on consult_session (created_at);
create index consult_session_market_created_idx on consult_session (market, created_at);
