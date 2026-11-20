-- LookMate's own tables (CLAUDE.md, "Data model"). One migration for the whole Phase 1 model, so the feature
-- sessions that build onboarding, intake, matching and tracking in parallel add code, not competing migrations.
-- Each later change is a new V<n> file.
--
-- Scale notes (docs/architecture.md): every place-bound row carries a `market` slug ('columbus-oh'), so a second city
-- is data, not a schema change; Salon Lofts is a brand on a location, not a hard-coded assumption. Embeddings live in
-- their own tables keyed by model, so a model swap re-embeds alongside the old vectors instead of migrating a column.

create extension if not exists vector;

-- Where a stylist works. Seeded by the admin (Salon Lofts' Columbus locations first).
create table salon_location (
    id         uuid primary key,
    brand      text        not null,                 -- e.g. 'SALON_LOFTS'; free text so other brands need no migration
    name       text        not null,                 -- e.g. 'Short North'
    market     text        not null,                 -- e.g. 'columbus-oh'
    address    text,
    city       text,
    state      text,
    zip        text,
    latitude   double precision,
    longitude  double precision,
    active     boolean     not null default true,
    created_at timestamptz not null
);

create index salon_location_market_idx on salon_location (market) where active;

-- A stylist's public profile: one per stylist org. DRAFT until the stylist accepts the terms and publishes it.
create table stylist_profile (
    org_id           uuid primary key references org (id),
    display_name     text        not null,
    location_id      uuid references salon_location (id),
    market           text        not null,
    bio              text,
    specialties      text[]      not null default '{}', -- service codes, see portfolio_photo.service
    booking_url      text,                              -- their Booksy / Vagaro / StyleSeat / GlossGenius link
    booking_platform text,                              -- detected from the URL, for reporting only
    instagram        text,                              -- handle, without the @
    price_tier       smallint check (price_tier between 1 and 4),
    status           text        not null default 'DRAFT' check (status in ('DRAFT', 'LIVE', 'HIDDEN')),
    published_at     timestamptz,
    created_at       timestamptz not null,
    updated_at       timestamptz not null
);

create index stylist_profile_live_idx on stylist_profile (market) where status = 'LIVE';

-- One portfolio photo and its tags. The original is private in object storage under `object_key`; the app hands
-- out short-lived signed links. A before/after pair is two rows pointing at each other through `pair_photo_id`.
create table portfolio_photo (
    id             uuid primary key,
    org_id         uuid        not null references org (id),
    object_key     text        not null unique,
    content_type   text        not null,
    size_bytes     bigint      not null,
    sha256         text,                 -- of the upload; set when the upload is confirmed (duplicate checks later)
    width          integer,
    height         integer,
    status         text        not null default 'PENDING_UPLOAD'
                       check (status in ('PENDING_UPLOAD', 'READY', 'HIDDEN', 'DELETED')),
    -- Tags, set by the stylist. Null until tagged; matching only uses READY photos with service and hair type set.
    service        text check (service in ('CUT', 'COLOR', 'BALAYAGE', 'HIGHLIGHTS', 'EXTENSIONS', 'BRAIDS', 'LOCS',
                                           'SILK_PRESS', 'PERM', 'TREATMENT', 'STYLING', 'OTHER')),
    hair_type      text check (hair_type in ('STRAIGHT', 'WAVY', 'CURLY', 'COILY')),
    texture        text check (texture in ('FINE', 'MEDIUM', 'THICK')),
    length         text check (length in ('PIXIE', 'SHORT', 'MEDIUM', 'LONG')),
    is_before      boolean     not null default false, -- the "before" half of a before/after pair
    pair_photo_id  uuid references portfolio_photo (id),
    position       integer     not null default 0,     -- the stylist's order on their profile
    uploaded_by    uuid        not null references app_user (id),
    created_at     timestamptz not null,
    updated_at     timestamptz not null,
    deleted_at     timestamptz
);

create index portfolio_photo_org_idx on portfolio_photo (org_id, position) where status <> 'DELETED';
create index portfolio_photo_match_filter_idx on portfolio_photo (service, hair_type) where status = 'READY';

-- A client. No account: they reach their matches through an emailed link (match_request.access_token_hash).
-- Contact details are personal data with a retention period (docs/data-retention.md).
create table client (
    id           uuid primary key,
    email        text,
    phone        text,
    first_name   text,
    hair_type    text check (hair_type in ('STRAIGHT', 'WAVY', 'CURLY', 'COILY')),
    texture      text check (texture in ('FINE', 'MEDIUM', 'THICK')),
    length       text check (length in ('PIXIE', 'SHORT', 'MEDIUM', 'LONG')),
    budget_tier  smallint check (budget_tier between 1 and 4),
    neighborhood text,
    market       text        not null,
    past_issues  text[]      not null default '{}', -- quiz answers: what went wrong with past stylists
    notes        text,
    created_at   timestamptz not null,
    anonymised_at timestamptz
);

-- One "find me a stylist for this look" request: the quiz answers live on the client, the look lives here.
-- The inspiration photo is private and deleted after the retention period; the row and its embedding stay
-- (the embedding is not an image and can't be turned back into one), so matches stay explainable.
create table match_request (
    id                        uuid primary key,
    client_id                 uuid        not null references client (id),
    market                    text        not null,
    service                   text check (service in ('CUT', 'COLOR', 'BALAYAGE', 'HIGHLIGHTS', 'EXTENSIONS', 'BRAIDS', 'LOCS',
                                                      'SILK_PRESS', 'PERM', 'TREATMENT', 'STYLING', 'OTHER')),
    inspiration_object_key    text unique,
    inspiration_content_type  text,
    inspiration_sha256        text,
    inspiration_deleted_at    timestamptz,
    access_token_hash         text        not null unique, -- SHA-256 of the client's link token
    status                    text        not null default 'NEW'
                                  check (status in ('NEW', 'MATCHING', 'IN_REVIEW', 'PUBLISHED', 'CLOSED', 'FAILED')),
    status_reason             text,
    reviewed_by               uuid references app_user (id),
    published_at              timestamptz,
    created_at                timestamptz not null,
    updated_at                timestamptz not null
);

create index match_request_queue_idx on match_request (status, created_at);
create index match_request_client_idx on match_request (client_id);

-- Image embeddings, one row per image per model. Dimension is left open until the model is chosen; the matching
-- module adds an HNSW index on a cast to the chosen dimension (`(embedding::vector(768))`, partial on its model)
-- when the portfolio count makes an exact scan slow. Below ~100k vectors an exact scan is a few milliseconds.
create table portfolio_photo_embedding (
    photo_id   uuid        not null references portfolio_photo (id),
    model      text        not null, -- provider and model id, e.g. 'replicate:siglip-so400m'
    embedding  vector      not null,
    created_at timestamptz not null,
    primary key (photo_id, model)
);

create table match_request_embedding (
    match_request_id uuid        not null references match_request (id),
    model            text        not null,
    embedding        vector      not null,
    created_at       timestamptz not null,
    primary key (match_request_id, model)
);

-- The ranked stylists for a request. The system writes score, reason, evidence and system_rank; the admin's review
-- sets final_rank and hidden (admin_override says they changed something). The client sees rows with final_rank set
-- and hidden false, ordered by final_rank.
create table match (
    id                 uuid primary key,
    match_request_id   uuid        not null references match_request (id),
    org_id             uuid        not null references org (id),
    score              double precision not null,
    reason             text        not null,               -- e.g. "12 similar cuts on wavy hair"
    evidence_photo_ids uuid[]      not null default '{}',  -- the portfolio photos that earned the match, best first
    system_rank        integer     not null,
    final_rank         integer,
    hidden             boolean     not null default false,
    admin_override     boolean     not null default false,
    admin_note         text,
    created_at         timestamptz not null,
    updated_at         timestamptz not null,
    unique (match_request_id, org_id)
);

create index match_org_idx on match (org_id, created_at);

-- What happened after a match, for the pilot metrics. Append-only. VIEWED and CLICKED_BOOK are recorded by the app;
-- BOOKED and REBOOKED are entered by the admin (or the stylist) until booking platforms report them.
create table match_event (
    id               uuid primary key,
    match_id         uuid        references match (id),          -- null for a VIEWED of the whole list
    match_request_id uuid        not null references match_request (id),
    org_id           uuid        references org (id),
    type             text        not null check (type in ('VIEWED', 'CLICKED_BOOK', 'BOOKED', 'REBOOKED')),
    source           text        not null check (source in ('CLIENT', 'ADMIN', 'STYLIST')),
    recorded_by      uuid references app_user (id),               -- null when the client did it
    note             text,
    occurred_at      timestamptz not null,
    created_at       timestamptz not null
);

create index match_event_type_time_idx on match_event (type, occurred_at);
create index match_event_org_idx on match_event (org_id, type, occurred_at);
create trigger match_event_append_only before update on match_event
    for each row execute function refuse_update();
