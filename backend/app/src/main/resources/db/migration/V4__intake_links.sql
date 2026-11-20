-- Intake (docs/modules/intake.md). A client's links: the first comes with the request, another with the "your matches
-- are ready" email (we keep only hashes, so a token can't be sent twice). Each has its own expiry; a wrong or expired
-- token is a 404. match_request.access_token_hash keeps the first link's hash, as V2 requires.
create table match_request_link (
    token_hash       text        primary key, -- SHA-256 (hex) of the token in the link
    match_request_id uuid        not null references match_request (id),
    purpose          text        not null check (purpose in ('CREATED', 'MATCHES_READY')),
    created_at       timestamptz not null,
    expires_at       timestamptz not null
);

create index match_request_link_request_idx on match_request_link (match_request_id);

-- The size the client declared for the inspiration upload; the presigned PUT is bound to it.
alter table match_request add column inspiration_size_bytes bigint;

-- The abuse guard counts a day's open requests per email address.
create index client_email_recent_idx on client (lower(email), created_at);
