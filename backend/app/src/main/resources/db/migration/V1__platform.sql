-- Platform tables carried over from pokemans (identity, consent, email outbox), as they stood there after its
-- migrations V1-V38. Auth0 proves who a user is (and ADMIN, with MFA); STYLIST access comes from membership here.

create function refuse_update() returns trigger language plpgsql as $$
begin
    raise exception '% is append-only: add a new row instead of updating', tg_table_name;
end;
$$;

create table org (
    id         uuid primary key,
    name       text        not null,
    type       text        not null check (type in ('STYLIST')),
    created_at timestamptz not null
);

create table app_user (
    id         uuid primary key,
    subject    text        not null unique, -- OIDC "sub", e.g. google-oauth2|1054...
    email      text,
    created_at timestamptz not null
);

-- A membership ends by recording who, when and why on its row, never by deleting it. Someone removed can be invited back.
create table membership (
    id         uuid primary key,
    org_id     uuid        not null references org (id),
    user_id    uuid        not null references app_user (id),
    role       text        not null check (role in ('STYLIST')),
    created_at timestamptz not null,
    ended_at   timestamptz,
    ended_by   uuid references app_user (id),
    end_reason text
);

create unique index membership_active_once on membership (org_id, user_id) where ended_at is null;
create index membership_user_idx on membership (user_id);

-- The link is the only thing that counts: whoever opens it while signed in joins the org.
create table invite (
    id          uuid primary key,
    org_id      uuid        not null references org (id),
    role        text        not null check (role in ('STYLIST')),
    token_hash  text        not null unique, -- SHA-256 of the token; the token itself is shown once
    created_by  uuid        not null references app_user (id),
    created_at  timestamptz not null,
    expires_at  timestamptz not null,
    accepted_by uuid references app_user (id),
    accepted_at timestamptz,
    revoked_at  timestamptz,
    revoked_by  uuid references app_user (id)
);

create index invite_org_idx on invite (org_id);

-- Who agreed to which version of an org's terms. Append-only.
create table consent_record (
    id            uuid primary key,
    org_id        uuid        not null references org (id),
    user_id       uuid        not null references app_user (id),
    terms_version text        not null,
    terms_sha256  text        not null, -- hash of the exact text shown
    accepted_at   timestamptz not null
);

create index consent_record_org_user_idx on consent_record (org_id, user_id);
create trigger consent_record_append_only before update on consent_record
    for each row execute function refuse_update();

-- Every email we mean to send, sent or not. Resend gets the row id as its idempotency key, so a retry after a lost
-- answer is never a second email. A row moves PENDING -> SENDING -> SENT (or FAILED, SKIPPED, CANCELLED). Once done
-- with and old enough, its address and content are cleared (EmailRetention); the row stays as the record.
create table email_message (
    id                 uuid primary key,
    dedupe_key         text        not null unique,
    template           text        not null,
    recipient_user_id  uuid        references app_user (id),
    recipient_email    text,
    org_id             uuid,
    subject_id         uuid, -- what the email is about (a match request, say), for cancelling what hasn't gone
    subject            text,
    html_body          text,
    text_body          text,
    status             text        not null check (status in ('PENDING', 'SENDING', 'SENT', 'FAILED', 'SKIPPED', 'CANCELLED')),
    status_reason      text,
    attempts           integer     not null default 0,
    next_attempt_at    timestamptz not null,
    last_error         text,
    provider_id        text,
    created_at         timestamptz not null,
    sent_at            timestamptz,
    updated_at         timestamptz not null,
    content_cleared_at timestamptz
);

create index email_message_due on email_message (next_attempt_at) where status = 'PENDING';
create index email_message_stuck on email_message (updated_at) where status = 'SENDING';
create index email_message_subject on email_message (subject_id, template) where subject_id is not null;
create index email_message_recipient on email_message (recipient_user_id, template, org_id, created_at);
create index email_message_content_kept on email_message (created_at)
    where content_cleared_at is null and status in ('SENT', 'FAILED', 'SKIPPED', 'CANCELLED');
