-- Indexes for tracking (docs/tracking.md). V2's match_event indexes serve range scans by type and by stylist; these
-- serve the per-match and per-request lookups: the "Book" click dedupe, "already booked?", the VIEWED-once-a-day check,
-- and the joins from a request or a match to its events in the metrics.
create index match_event_match_idx on match_event (match_id, type, occurred_at) where match_id is not null;
create index match_event_request_idx on match_event (match_request_id, type, occurred_at);
