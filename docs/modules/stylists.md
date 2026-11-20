# Module `stylists`

Stylist onboarding (profile, Salon Lofts location, booking link) and the portfolio (private photos plus tags).
Package `com.lookmate.app.stylists`. Owns the tables `salon_location`, `stylist_profile`, `portfolio_photo`
(all from `V2__matching_domain.sql`).

Everything is package-private except the public service below, its records and enums, and the events. Other modules
call **only** these; never the repositories or the tables.

## Public service: `com.lookmate.app.stylists.Stylists`

```java
@Service
public class Stylists {

    /** A LIVE stylist as clients may see them. */
    public record LiveProfile(UUID orgId, String displayName, String market, Location location, String bio,
                              List<ServiceType> specialties, String bookingUrl, BookingPlatform bookingPlatform,
                              String instagram, Integer priceTier, boolean demo) { }

    /** Any stylist profile with its status: for admin screens (DRAFT, LIVE, HIDDEN). */
    public record ProfileSummary(UUID orgId, String displayName, String market, ProfileStatus status,
                                 UUID locationId, String bookingUrl, int readyPhotos, boolean demo) { }

    public record Location(UUID id, String brand, String name, String market, String address, String city,
                           String state, String zip, Double latitude, Double longitude) { }

    /** A READY, tagged portfolio photo. pairPhotoId is the other half of a before/after pair, or null. */
    public record PortfolioPhoto(UUID photoId, UUID orgId, ServiceType service, HairType hairType, Texture texture,
                                 HairLength length, boolean before, UUID pairPhotoId, int position,
                                 Integer width, Integer height) { }

    /** The profile if the org's profile is LIVE; empty for DRAFT, HIDDEN, missing or a non-stylist org. */
    public Optional<LiveProfile> liveProfile(UUID orgId);

    /** Every LIVE profile in the market, by display name (the admin's "add a stylist" picker, client browsing). */
    public List<LiveProfile> liveProfiles(String market);

    /** Any profile, whatever its status, or empty. No access check: callers decide who may see it. */
    public Optional<ProfileSummary> profile(UUID orgId);

    /**
     * READY photos of LIVE stylists in the market, tagged with this service and one of these hair types,
     * ordered by org then the stylist's own position. Untagged photos (service or hair type null) never appear.
     * An empty hairTypes means any hair type.
     */
    public List<PortfolioPhoto> readyPhotos(String market, ServiceType service, Collection<HairType> hairTypes);

    /** All READY photos of one stylist (LIVE or not), in the stylist's order. Tags may be null. */
    public List<PortfolioPhoto> readyPhotosOf(UUID orgId);

    /** One photo if it is READY (whatever its tags), or empty if pending, hidden or deleted. */
    public Optional<PortfolioPhoto> readyPhoto(UUID photoId);

    /**
     * A short-lived signed GET link (lookmate.storage.download-url-ttl, 15 min) for a READY photo, or empty.
     * Never a public URL. Ask again for each page view; don't store it.
     */
    public Optional<URI> photoUrl(UUID photoId);

    /**
     * The stored bytes of a READY photo (location and device metadata already stripped), or empty. For the
     * embedding worker; call it off the request path.
     */
    public Optional<byte[]> photoBytes(UUID photoId);

    /** Active locations in a market, by name. */
    public List<Location> locations(String market);
}
```

### Enums (public, same package)

| Enum | Values (match the V2 check constraints) |
|---|---|
| `ServiceType` | `CUT, COLOR, BALAYAGE, HIGHLIGHTS, EXTENSIONS, BRAIDS, LOCS, SILK_PRESS, PERM, TREATMENT, STYLING, OTHER` |
| `HairType` | `STRAIGHT, WAVY, CURLY, COILY` |
| `Texture` | `FINE, MEDIUM, THICK` |
| `HairLength` | `PIXIE, SHORT, MEDIUM, LONG` |
| `ProfileStatus` | `DRAFT, LIVE, HIDDEN` |
| `BookingPlatform` | `BOOKSY, VAGARO, STYLESEAT, GLOSSGENIUS, SQUARE, OTHER` |

### Events (published inside the transaction; use `@ApplicationModuleListener` or `@TransactionalEventListener`)

```java
public record PortfolioPhotoReady(UUID photoId, UUID orgId) { }     // confirmed upload, or a hidden photo shown again
public record PortfolioPhotoRemoved(UUID photoId, UUID orgId) { }   // hidden or deleted: drop it from matching
public record PortfolioPhotoTagged(UUID photoId, UUID orgId) { }    // tags changed
public record StylistPublished(UUID orgId) { }                      // profile went LIVE
public record StylistHidden(UUID orgId) { }                         // profile went HIDDEN
```

Matching can embed on `PortfolioPhotoReady` or poll; either way, filter on `readyPhotos(...)` at match time, since
a photo can be hidden or deleted after it was embedded.

## HTTP API

Admin (`/api/admin/**`, ADMIN with MFA):

| Method | Path | What |
|---|---|---|
| GET | `/api/admin/locations?market=` | All locations (active and not), optionally one market |
| POST | `/api/admin/locations` | Create a location (brand, name, market, address, city, state, zip, lat/long) |
| PUT | `/api/admin/locations/{id}` | Update, including `active` |
| GET | `/api/admin/stylists` | Every stylist org with its profile status, photo count and demo flag |
| POST | `/api/admin/stylists/{orgId}/demo` | Mark a stylist as made up (proof of concept) |
| POST | `/api/admin/stylists/demo/hide` | Hide every LIVE demo stylist at once |
| POST | `/api/admin/stylists/{orgId}/hide` | Hide one stylist from every result (recommendations and the directory); they can publish again |

Public (no sign-in, rate-limited per IP with intake's limiter, `rate-limit.browse-per-minute`):

| Method | Path | What |
|---|---|---|
| GET | `/api/public/stylists?market=&service=&hairType=&page=` | The open directory, for visitors without a request (`/stylists`, from `/find`): LIVE stylists only, 12 a page from 0, real stylists before sample ones, then by name |
| GET | `/api/public/match-requests/{token}/stylists?service=&hairType=&page=` | The same page scoped to a client's link (served by intake with `StylistDirectory`): the request's market, a bad token is 404. The client page (`/m/:token/stylists`) uses this one |

Stylist (`/api/orgs/{orgId}/...`, member of the STYLIST org or ADMIN; anyone else gets 404):

| Method | Path | What |
|---|---|---|
| GET | `/api/locations?market=` | Active locations for the profile form (any signed-in user) |
| GET | `/api/orgs/{orgId}/stylist` | Home: terms accepted?, profile (or null), photo counts, what's missing to publish |
| PUT | `/api/orgs/{orgId}/stylist/profile` | Create or update the profile (stays DRAFT/LIVE/HIDDEN as it was) |
| POST | `/api/orgs/{orgId}/stylist/publish` | LIVE; needs accepted terms, a location, an https booking URL and 3+ tagged READY photos |
| POST | `/api/orgs/{orgId}/stylist/hide` | HIDDEN |
| GET | `/api/orgs/{orgId}/photos` | READY and HIDDEN photos, in order, with signed view URLs |
| POST | `/api/orgs/{orgId}/photos` | Upload slot: `{contentType, sizeBytes}` → presigned PUT (needs accepted terms) |
| POST | `/api/orgs/{orgId}/photos/{photoId}/confirm` | Checks type/size, hashes, strips location/device EXIF, READY |
| PUT | `/api/orgs/{orgId}/photos/{photoId}/tags` | `{service, hairType, texture, length, before, pairPhotoId}` |
| PUT | `/api/orgs/{orgId}/photos/order` | `{photoIds: [...]}`, the full new order |
| POST | `/api/orgs/{orgId}/photos/{photoId}/hide` / `show` | Hide from the profile and matching, or show again |
| DELETE | `/api/orgs/{orgId}/photos/{photoId}` | Status DELETED and the object deleted from storage |

Booking URL: must be `https://`; the platform is detected from the host (`booksy.com`, `vagaro.com`,
`styleseat.com`, `glossgenius.com`, `square.site`/`squareup.com`, else `OTHER`).
Photo cap: `lookmate.storage.max-photos-per-stylist` (READY + HIDDEN + recent pending uploads).

## The public directory (2026-11-20)

Clients choose; we recommend (CLAUDE.md): every client can browse every LIVE stylist in a market (default
`lookmate.default-market`). `StylistDirectory` answers `/api/public/stylists` with, per stylist: `orgId`, `displayName`,
`locationName`, `city`, `specialties`, up to 3 READY `photoUrls` (short-lived presigned links, those matching the
filters first), `demo` (label it "Sample stylist") and `instagram`. Nothing else: no email, booking link or other
internal id. Responses are `Cache-Control: no-store` (the photo links expire).

- **Filters:** `service` and `hairType` (the enums above). A stylist matches with a READY photo tagged with both (or
  whichever is given); a `service` alone also matches the stylist's specialties.
- **Why both endpoints:** the UX design (`docs/ux/flows.md`) scopes browsing to the client's link, which ties it to
  the request; the decision of 2026-11-20 also wants the directory reachable from `/find` before any request exists.
  The open listing only shows what LIVE stylists publish for clients, offers no Book, and is rate-limited.
- **Book:** not in the JSON. From the client's own page (`/m/:token/stylists`) the web app links to tracking's
  `/api/public/book-stylist/{token}/{orgId}`, which records the click against the client's request and redirects.
  Signed out (`/stylists`, reached from `/find`) there is no Book: the card says "Start a request to book". We chose
  this over an untracked public redirect: it is the simpler safe option (no booking link leaves the server except as a
  tracked redirect) and keeps every booking click attributable (docs/tracking.md).

## Notes for the other modules

- **Photo metadata.** Stripped with `storage/PhotoMetadata`, shared with intake: a JPEG or PNG copied byte for byte
  without EXIF/XMP/comments/text, keeping only the orientation.
- **Width and height** are the stored pixel dimensions, before the EXIF orientation is applied (a portrait phone photo
  with orientation 6 reads as landscape here). Browsers apply the orientation when showing it.
- **`sha256`** is of the upload as received (before stripping), so the same photo uploaded twice is caught as a
  duplicate within one stylist's portfolio.
- **Before/after pairs.** Tagging photo A with `pairPhotoId = B, before = true` sets B's pair to A and B's `before` to
  false; whatever A or B were paired with before is unpaired. A photo is in at most one pair. Deleting a photo unpairs
  its partner.
- **Not checked yet:** whether a pending upload slot is ever used. Rows left in `PENDING_UPLOAD` stop counting toward the
  cap after an hour; a cleanup job for them (and their objects, if any) is a follow-up.
- **Defaults to confirm:** `lookmate.stylists.min-tagged-photos-to-publish: 3` (application.yml).

## Demo stylists (proof of concept)

Until real stylists join, the admin creates made-up ones at `/admin/demo` (Zach, 2026-11-20). The page creates each
one through the normal API, acting as the admin: an org named "Demo: <name>", a profile on one of the active locations,
`stylist_profile.demo = true` (V3), five tagged photos (before/after pairs included) and publish. Photos are drawn
placeholders showing their tags, unless the admin picks real photos named as in `web/src/admin/demoStylists.ts`
(`maya-1.jpg` to `nia-5.jpg`). Booking links go to `https://example.com/book/<slug>`.

- **Matching and client screens:** demo stylists are LIVE and matched like real ones. `LiveProfile.demo` is true for
  them: label them ("Sample stylist") wherever a client sees them. Placeholder photos make similarity scores
  meaningless; use real photos for a matching demo.
- **When real stylists join:** "Hide all demo stylists" on `/admin/demo` (`POST /api/admin/stylists/demo/hide`).
- **Locations:** not seeded. Zach enters the Salon Lofts Columbus locations on `/admin/locations`.

## Follow-ups

- **Abandoned upload slots (agreed to leave for now).** No cleanup job; see above.
