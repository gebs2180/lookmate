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
                              String instagram, Integer priceTier) { }

    /** Any stylist profile with its status: for admin screens (DRAFT, LIVE, HIDDEN). */
    public record ProfileSummary(UUID orgId, String displayName, String market, ProfileStatus status,
                                 UUID locationId, String bookingUrl, int readyPhotos) { }

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
| GET | `/api/admin/stylists` | Every stylist org with its profile status and photo count |

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

## Notes for the other modules

- **Photo metadata.** `MetadataStripper` (package-private, ported from pokemans' `intake/ExactCopy`) copies a JPEG or
  PNG byte for byte without EXIF/XMP/comments/text, keeping only the orientation. Intake needs the same for
  inspiration photos; if it wants to share it, move it to `storage` as a public class in a follow-up rather than
  calling into `stylists`. It uses `com.drewnoakes:metadata-extractor:2.22.0` (added to `backend/app/build.gradle.kts`).
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
