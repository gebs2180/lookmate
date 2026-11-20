package com.lookmate.app.tracking;

import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    PublicTrackingController(TrackingService tracking) {
        this.tracking = tracking;
    }

    /**
     * Records the click and sends the client on to the stylist's own booking page, tagged with UTM parameters.
     * No-referrer, so the client's link token in this URL is never passed to the booking site.
     */
    @GetMapping("/api/public/book/{token}/{matchId}")
    ResponseEntity<Void> book(@PathVariable String token, @PathVariable UUID matchId) {
        return tracking.clickBook(token, matchId)
                .map(link -> ResponseEntity.status(HttpStatus.FOUND)
                        .location(link)
                        .cacheControl(CacheControl.noStore())
                        .header("Referrer-Policy", "no-referrer")
                        .<Void>build())
                .orElseThrow(PublicTrackingController::notFound);
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
