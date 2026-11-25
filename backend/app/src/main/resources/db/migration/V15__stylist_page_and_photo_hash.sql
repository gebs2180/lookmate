-- The public stylist page /s/<slug> and the duplicate portfolio photo check (ideas 4 and 7 of
-- docs/research/feature-ideas.md, decided by Zach 2026-11-25).

-- The stylist's own address on Combpatible: combpatible.app/s/<slug>. Set from the display name at the first save and
-- editable on the profile. Lower-case letters, digits and hyphens, 3 to 40, starting and ending with a letter or digit.
alter table stylist_profile add column slug text;

-- Existing profiles get one from their name, as ProfileFieldRules.slugFrom does (without its accent folding), with
-- -2, -3... where two names give the same one.
with base as (
    select org_id, created_at,
           left(trim(both '-' from regexp_replace(lower(display_name), '[^a-z0-9]+', '-', 'g')), 34) as b
    from stylist_profile
), padded as (
    select org_id, created_at,
           case when length(trim(both '-' from b)) = 0 then 'stylist'
                when length(trim(both '-' from b)) < 3 then trim(both '-' from b) || '-stylist'
                else trim(both '-' from b) end as b
    from base
), numbered as (
    select org_id, b, row_number() over (partition by b order by created_at, org_id) as n from padded
)
update stylist_profile p
set slug = case when n.n = 1 then n.b else n.b || '-' || n.n end
from numbered n
where n.org_id = p.org_id;

-- The app always sets one from the name (ProfileService); a row inserted any other way (seed SQL, a fixture) gets a
-- random one rather than failing, and can be renamed on the profile.
alter table stylist_profile
    alter column slug set default ('s-' || substr(md5(gen_random_uuid()::text), 1, 12)),
    alter column slug set not null,
    add constraint stylist_profile_slug_unique unique (slug),
    add constraint stylist_profile_slug_format check (slug ~ '^[a-z0-9][a-z0-9-]{1,38}[a-z0-9]$');

-- The duplicate check at upload: a photo whose SHA-256 (of the stored bytes, metadata already stripped) is already kept
-- by another stylist is refused. sha256 has been stored since V2; this makes the lookup across stylists cheap.
create index portfolio_photo_sha256 on portfolio_photo (sha256) where status in ('READY', 'HIDDEN');

-- "Book" on a stylist's own page (owned by tracking). Kept apart from match_event, which is always tied to a client's
-- request: clients who come through a stylist's own page are the stylist's, and are never charged for (Zach,
-- 2026-11-25). Nothing about the client is stored: no IP, no request, no cookie.
create table profile_click (
    id          uuid        primary key,
    org_id      uuid        not null references org (id),
    market      text        not null,
    -- Always PROFILE for now: the tracking source, alongside the recommended and directory clicks in match_event.
    source      text        not null default 'PROFILE' check (source = 'PROFILE'),
    -- How the client reached the page, from the link the stylist shared: their copied link, the QR card or the
    -- link-in-bio version. Anything else is LINK.
    via         text        not null check (via in ('LINK', 'QR', 'BIO')),
    occurred_at timestamptz not null
);

create index profile_click_org on profile_click (org_id, occurred_at);
create index profile_click_market on profile_click (market, occurred_at);
