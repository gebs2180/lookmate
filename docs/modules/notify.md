# Module `notify`

The email outbox (`email_outbox`), sending through Resend, and retention of sent content. From pokemans. Other modules
queue email only through the public services below, in their own transaction; a worker sends what is due.

- **Every email is stored.** One to a user with no email on file, or while sending is off (`lookmate.email.*`), is stored
  as SKIPPED with the reason, content kept, so local dev and tests can read what would have gone out.
- **Dedupe key** per email (unique): queuing the same key twice is a no-op, which is what makes the senders idempotent.
- **Templates** (`EmailOutbox.Template`): `MATCHES_READY`, `MATCH_LIST_LINK`, `PICKS_ADDED`, `MATCH_REQUEST_RECEIVED`
  (no longer sent), `STYLIST_INVITE`, `STYLIST_DIGEST`.
- **Retention** (`EmailRetention`) clears stored content on schedule: docs/data-retention.md.

## Public services

### `ClientEmails` (intake)

Clients have no account; intake passes the address. `listLink`, `picksAdded`, `matchesReady`, `noCloseMatch`,
`cancelMatchesReady`, `cancelPendingForAddressChange`. See docs/modules/intake.md.

### `StylistEmails` (tracking), 2026-11-25

```java
public record DigestClient(String firstName, String bookedPath, String rebookedPath, boolean booked) {}
public record WeeklyDigest(String stylistName, LocalDate weekStart, long clientsShown, List<DigestClient> tapped,
                           String statsLine, String statsPath, String settingsPath, String unsubscribePath,
                           String statusConfirmPath) {}

/** Queues the Monday digest to one member; false if already queued. Dedupe key stylist-digest:<digestId>:<userId>. */
public boolean weeklyDigest(UUID digestId, UUID orgId, UUID userId, WeeklyDigest digest, Instant now)
```

Sent to the user's login email (`Notifier`, via identity's `Directory`). Paths are relative; the email makes them
absolute with `lookmate.email.app-url`. Copy says Combpatible and names clients by first name only. A booked client
shows "Booking recorded" and only the Rebooked button. `statusConfirmPath`, when set, adds "Still taking new clients?
Confirm" (not set yet: docs/modules/stylists.md, "Follow-ups"). The email carries the stylist's one-time links, so its
stored content is as sensitive as those links until retention clears it; they expire after 14 days anyway. How the
digest is built and sent: docs/tracking.md, "Weekly digest".
