-- Tracking (docs/tracking.md): voiding a wrongly entered event, and indexes for the per-match and per-request lookups.

-- match_event stays append-only. A mistake is undone by a VOIDED row that points at it (voids_event_id) and says why
-- (note). Every count reads match_event_live, which leaves out VOIDED rows and the rows they void. An event can be
-- voided once; a VOIDED row can't itself be voided (re-record the event instead).
alter table match_event add column voids_event_id uuid references match_event (id);
alter table match_event drop constraint match_event_type_check;
alter table match_event add constraint match_event_type_check
    check (type in ('VIEWED', 'CLICKED_BOOK', 'BOOKED', 'REBOOKED', 'VOIDED'));
alter table match_event add constraint match_event_void_shape
    check ((type = 'VOIDED') = (voids_event_id is not null) and (type <> 'VOIDED' or note is not null));
create unique index match_event_voids_idx on match_event (voids_event_id) where voids_event_id is not null;

create view match_event_live as
select e.*
from match_event e
where e.type <> 'VOIDED'
  and not exists (select 1 from match_event v where v.voids_event_id = e.id);

-- V2's indexes serve range scans by type and by stylist; these serve the click dedupe, "already booked?", the
-- VIEWED-once-a-day check, and the joins from a match or a request to its events in the metrics.
create index match_event_match_idx on match_event (match_id, type, occurred_at) where match_id is not null;
create index match_event_request_idx on match_event (match_request_id, type, occurred_at);
