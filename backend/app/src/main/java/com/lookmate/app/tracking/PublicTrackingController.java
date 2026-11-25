package com.lookmate.app.tracking;

import com.lookmate.app.stylists.Stylists;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The client's side of tracking, without an account: the "Book" link and the "I opened my matches" ping. Both are
 * gated by the link token from the client's email. A wrong token, a match that isn't theirs or isn't shown, and a
 * booking link that isn't https all answer the same 404.
 */
@RestController
class PublicTrackingController {

    private final TrackingService tracking;
    private final Stylists stylists;
    private final ProfileClicks profileClicks;

    PublicTrackingController(TrackingService tracking, Stylists stylists, ProfileClicks profileClicks) {
        this.tracking = tracking;
        this.stylists = stylists;
        this.profileClicks = profileClicks;
    }

    /**
     * Records the click and sends the client on to the stylist's own booking page, tagged with UTM parameters.
     * No-referrer, so the client's link token in this URL is never passed to the booking site.
     */
    @GetMapping("/api/public/book/{token}/{matchId}")
    ResponseEntity<Void> book(@PathVariable String token, @PathVariable UUID matchId) {
        return tracking.clickBook(token, matchId)
                .map(PublicTrackingController::redirect)
                .orElseThrow(PublicTrackingController::notFound);
    }

    private static ResponseEntity<Void> redirect(java.net.URI link) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(link)
                .cacheControl(CacheControl.noStore())
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    /**
     * "Book" on a stylist from the directory: records a click with no match, attributed to the client's request through
     * their link token, then the same redirect as above ({@code utm_content=directory}). Without a valid token there is
     * no redirect at all: the directory only offers Book to clients with a link (docs/tracking.md).
     */
    @GetMapping("/api/public/book-stylist/{token}/{orgId}")
    ResponseEntity<Void> bookStylist(@PathVariable String token, @PathVariable UUID orgId) {
        return tracking.clickDirectory(token, orgId)
                .map(PublicTrackingController::redirect)
                .orElseThrow(PublicTrackingController::notFound);
    }

    /**
     * "Book" on a stylist's own page ({@code /s/<slug>}): records a PROFILE click ({@code profile_click}, apart from
     * recommended and directory clicks: these clients came through the stylist's own link and are never charged for),
     * then the same redirect with {@code utm_content=profile}. No token: the page is public. LIVE stylists only, never
     * a demo one (they have no page). {@code via}: {@code qr} or {@code bio} from the shared link, else LINK.
     */
    @GetMapping("/api/public/stylists/{orgId}/book")
    ResponseEntity<Void> bookFromProfile(@PathVariable UUID orgId, @RequestParam(required = false) String via) {
        Optional<Stylists.LiveProfile> stylist = stylists.liveProfile(orgId).filter(p -> !p.demo());
        java.net.URI link = stylist.flatMap(p -> BookingLinks.tagged(p.bookingUrl(), p.market(), BookingLinks.PROFILE))
                .orElseThrow(PublicTrackingController::notFound);
        profileClicks.record(orgId, stylist.get().market(), ProfileClicks.Via.of(via));
        return redirect(link);
    }

    /** Idempotent: the first call of the day records a VIEWED, later ones do nothing. */
    @PostMapping("/api/public/match-requests/{token}/viewed")
    ResponseEntity<Void> viewed(@PathVariable String token) {
        if (!tracking.viewed(token)) {
            throw notFound();
        }
        return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
    }
}
