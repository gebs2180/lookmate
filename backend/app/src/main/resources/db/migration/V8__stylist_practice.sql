-- What to expect in a stylist's chair (Zach, 2026-11-20; docs/research/beyond-the-hair.md): practice labels the stylist
-- ticks, languages they can do a consultation in, from-prices per service and whether they offer a consultation.
-- All optional, none needed to go live. Shown to clients on the profile only: no directory filters, never used in
-- ranking or matching. No political, religious or identity labels, ever (the check below is the whole list).
alter table stylist_profile
    add column practice_labels text[] not null default '{}'
        check (practice_labels <@ array ['GENDER_NEUTRAL_PRICING', 'LGBTQ_AFFIRMING', 'SENSORY_FRIENDLY', 'KIDS_WELCOME']::text[]),
    -- Besides English, which is implied. Codes of the Java enum Language.
    add column languages text[] not null default '{}',
    -- Null: the stylist hasn't said.
    add column consultation text check (consultation in ('FREE', 'PAID', 'INCLUDED')),
    -- "Price depends on length and density. Ask at your consultation."
    add column price_depends_on_hair boolean not null default false;

-- A starting price, in whole US dollars, for a service the stylist offers (one of stylist_profile.specialties).
create table stylist_price (
    org_id       uuid    not null references stylist_profile (org_id) on delete cascade,
    service      text    not null check (service in ('CUT', 'COLOR', 'BALAYAGE', 'HIGHLIGHTS', 'EXTENSIONS', 'BRAIDS', 'LOCS',
                                                     'SILK_PRESS', 'PERM', 'TREATMENT', 'STYLING', 'OTHER')),
    from_dollars integer not null check (from_dollars between 1 and 10000),
    primary key (org_id, service)
);
