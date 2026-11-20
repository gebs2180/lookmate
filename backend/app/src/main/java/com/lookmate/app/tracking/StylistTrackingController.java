package com.lookmate.app.tracking;

import com.lookmate.app.identity.CurrentUser;
import com.lookmate.app.identity.OrgAccess;
import com.lookmate.app.identity.OrgType;
import com.lookmate.app.tracking.MatchEvents.Source;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** A stylist's own numbers, and bookings they enter for their own matches. Org-scoped through OrgAccess. */
@RestController
class StylistTrackingController {

    private final OrgAccess orgAccess;
    private final MetricsService metrics;
    private final TrackingService tracking;
    private final CurrentUser currentUser;

    StylistTrackingController(OrgAccess orgAccess, MetricsService metrics, TrackingService tracking, CurrentUser currentUser) {
        this.orgAccess = orgAccess;
        this.metrics = metrics;
        this.tracking = tracking;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/orgs/{orgId}/tracking/stats")
    MetricsService.StylistStats stats(@PathVariable UUID orgId) {
        orgAccess.require(orgId, OrgType.STYLIST);
        return metrics.stylistStats(orgId);
    }

    /** Only for a match of this org: another org's match is a 404, as if it didn't exist. */
    @PostMapping("/api/orgs/{orgId}/matches/{matchId}/events")
    @ResponseStatus(HttpStatus.CREATED)
    TrackingService.Recorded record(@PathVariable UUID orgId, @PathVariable UUID matchId,
                                    @Valid @RequestBody AdminTrackingController.RecordBooking request) {
        orgAccess.require(orgId, OrgType.STYLIST);
        // An admin calling the org route is recorded as the admin.
        Source source = currentUser.isAdmin() ? Source.ADMIN : Source.STYLIST;
        return tracking.recordBooking(matchId, orgId, request.type(), source, currentUser.id(), request.note(),
                request.occurredAt());
    }
}
