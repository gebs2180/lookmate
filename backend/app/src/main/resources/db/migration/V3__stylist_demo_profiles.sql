-- Made-up stylists for the proof of concept, until real ones join (Zach, 2026-11-20). A demo profile behaves like any
-- other (it can go LIVE and be matched), so screens that clients see can label it, and the admin can hide them all at
-- once (POST /api/admin/stylists/demo/hide).
alter table stylist_profile add column demo boolean not null default false;
