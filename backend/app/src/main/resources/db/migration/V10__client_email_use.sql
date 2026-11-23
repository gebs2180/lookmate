-- Each time an email address is set on a client request (with the quiz or on the results page), so the per-address cap
-- counts the link emails an address was sent, not how many clients have it now (review 2026-11-22, finding 4: setting
-- another address on a request used to reset the count). Only a hash of the lower-cased address is kept, and client
-- contact retention prunes rows after two days (the cap looks back one).
create table client_email_use (
    address_hash text        not null,
    used_at      timestamptz not null
);

create index client_email_use_address on client_email_use (address_hash, used_at);
