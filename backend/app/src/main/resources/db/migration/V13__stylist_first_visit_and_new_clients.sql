-- "Before your first visit" and "Taking new clients" (ideas 1 and 3 of docs/research/feature-ideas.md, decided by Zach
-- 2026-11-25). Both are shown to clients only: neither ever reaches matching eligibility or order.
alter table stylist_profile
    -- The stylist's own first-visit facts and note, all optional. A deposit is shown as a fact; we never collect money.
    add column colour_consultation_first boolean not null default false,
    add column deposit_for_new_clients   boolean not null default false,
    add column first_visit_note          text check (char_length(first_visit_note) <= 280),
    -- Whether they're taking new clients. Shown only: "not taking" never removes a stylist from matching or the directory.
    add column new_clients               text not null default 'TAKING'
        check (new_clients in ('TAKING', 'TAKING_WITH_WAIT', 'NOT_TAKING')),
    -- "Usually about N weeks out", only with TAKING_WITH_WAIT.
    add column new_clients_weeks         smallint check (new_clients_weeks between 1 and 26),
    -- When the stylist last set or confirmed the status; the dashboard asks again 30 days later.
    add column new_clients_confirmed_at  timestamptz not null default now(),
    add constraint stylist_profile_new_clients_weeks
        check ((new_clients = 'TAKING_WITH_WAIT') = (new_clients_weeks is not null));
