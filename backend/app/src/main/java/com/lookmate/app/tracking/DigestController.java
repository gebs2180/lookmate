package com.lookmate.app.tracking;

import com.lookmate.app.identity.CurrentUser;
import com.lookmate.app.identity.OrgAccess;
import com.lookmate.app.identity.OrgType;
import com.lookmate.app.stylists.NewClients;
import com.lookmate.app.tracking.MatchEvents.Source;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The weekly digest's links and switch (docs/tracking.md, "Weekly digest"). The Booked/Rebooked links need a signed-in
 * member of the link's org: a forwarded email gets anyone else a 404. The unsubscribe and "Still taking new clients?"
 * links need no sign-in.
 */
@RestController
class DigestController {

    /** BOOKED or REBOOKED; {@code occurredAt} defaults to now. */
    record DigestRecord(@NotNull MatchEvents.Type type, Instant occurredAt) {
    }

    record DigestSetting(boolean weeklyDigest) {
    }

    /** The new-client status the stylist just confirmed, to say back to them. */
    record NewClientsConfirmed(NewClients newClients) {
    }

    private final WeeklyDigests digests;
    private final OrgAccess orgAccess;
    private final CurrentUser currentUser;

    DigestController(WeeklyDigests digests, OrgAccess orgAccess, CurrentUser currentUser) {
        this.digests = digests;
        this.orgAccess = orgAccess;
        this.currentUser = currentUser;
    }

    @GetMapping("/api/digest-links/{token}")
    WeeklyDigests.LinkInfo link(@PathVariable String token) {
        return digests.info(token, this::requireMember, Instant.now());
    }

    @PostMapping("/api/digest-links/{token}")
    @ResponseStatus(HttpStatus.CREATED)
    TrackingService.Recorded record(@PathVariable String token, @Valid @RequestBody DigestRecord request) {
        Source source = currentUser.isAdmin() ? Source.ADMIN : Source.STYLIST;
        return digests.record(token, this::requireMember, request.type(), request.occurredAt(), source, currentUser.id(),
                Instant.now());
    }

    @GetMapping("/api/orgs/{orgId}/digest")
    DigestSetting setting(@PathVariable UUID orgId) {
        orgAccess.require(orgId, OrgType.STYLIST);
        return new DigestSetting(digests.enabledFor(orgId, currentUser.id()));
    }

    @PutMapping("/api/orgs/{orgId}/digest")
    DigestSetting update(@PathVariable UUID orgId, @RequestBody DigestSetting setting) {
        orgAccess.require(orgId, OrgType.STYLIST);
        digests.setEnabled(orgId, currentUser.id(), setting.weeklyDigest(), Instant.now());
        return new DigestSetting(digests.enabledFor(orgId, currentUser.id()));
    }

    /** POST, not GET, so a mail scanner opening the link doesn't unsubscribe anyone: the page has a button. */
    @PostMapping("/api/public/digest/unsubscribe/{token}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void unsubscribe(@PathVariable String token) {
        if (!digests.unsubscribe(token, Instant.now())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found");
        }
    }

    /** POST for the same reason: the page has a button. Confirms the status as it stands; changing it needs a sign-in. */
    @PostMapping("/api/public/digest/new-clients/{token}")
    NewClientsConfirmed confirmNewClients(@PathVariable String token) {
        return digests.confirmNewClients(token, Instant.now())
                .map(status -> new NewClientsConfirmed(status.newClients()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Link not found"));
    }

    private void requireMember(UUID orgId) {
        orgAccess.require(orgId, OrgType.STYLIST);
    }
}
