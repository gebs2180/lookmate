-- Intake (docs/modules/intake.md): the client's email is optional and asked after the reveal (CLAUDE.md, 2026-11-20).
-- client.email was already nullable (retention clears it). The quiz no longer sends one; the client may add it on their
-- results page (POST /api/public/match-requests/{token}/email), and may correct it a few times.

-- When the email was last set, and how many times it has been set for this request (capped,
-- lookmate.intake.max-email-changes-per-request). Requests made with an email before V7 count as set at creation.
alter table client add column email_set_at timestamptz;
alter table client add column email_changes smallint not null default 0;
update client set email_set_at = created_at, email_changes = 1 where email is not null;

-- The per-address cap on link emails (lookmate.intake.max-link-emails-per-address-per-day) counts by when each was set.
create index client_email_set_idx on client (lower(email), email_set_at) where email is not null;

-- Contact retention finds clients due for anonymising by age, with or without an email.
create index client_retention_idx on client (created_at) where anonymised_at is null;

-- V4's index for the per-email cap on open requests, which is gone (creation is limited per IP instead).
drop index if exists client_email_recent_idx;
