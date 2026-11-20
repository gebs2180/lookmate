package com.lookmate.app.matching;

import com.lookmate.app.identity.CurrentUser;
import com.lookmate.app.intake.MatchRequests;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only (by path, SecurityConfig): the match monitor, the review queue, one request's review, and the fairness report. */
@RestController
@Validated
class AdminMatchController {

    record Order(@NotNull @Size(max = 100) List<@NotNull UUID> matchIds) {
    }

    record MatchChange(boolean hidden, @Size(max = 2000) String note) {
    }

    record AddStylist(@NotNull UUID orgId, @Size(max = 2000) String note) {
    }

    private final ReviewService review;
    private final CurrentUser currentUser;
    private final String defaultMarket;

    AdminMatchController(ReviewService review, CurrentUser currentUser, @Value("${lookmate.default-market}") String defaultMarket) {
        this.review = review;
        this.currentUser = currentUser;
        this.defaultMarket = defaultMarket;
    }

    /**
     * The monitor: the most recent requests in every status, newest first, with what each client was shown and did
     * (views, Book clicks and bookings per match, directory picks), and whether lists wait for review.
     */
    @GetMapping("/api/admin/match-monitor")
    ReviewService.Monitor monitor(@RequestParam(required = false) @Size(max = 64) String market,
                                  @RequestParam(defaultValue = "50") @Min(1) @Max(200) int limit) {
        return review.monitor(market == null || market.isBlank() ? null : market, limit);
    }

    /** Requests in a status, oldest first: IN_REVIEW is the queue; FAILED and MATCHING show what's stuck. */
    @GetMapping("/api/admin/match-requests")
    List<ReviewService.QueueItem> queue(
            @RequestParam(defaultValue = "IN_REVIEW") @Pattern(regexp = "MATCHING|IN_REVIEW|PUBLISHED|FAILED") String status) {
        return review.queue(MatchRequests.Status.valueOf(status));
    }

    @GetMapping("/api/admin/match-requests/{id}")
    ReviewService.Review get(@PathVariable UUID id) {
        return review.review(id);
    }

    @GetMapping("/api/admin/match-requests/{id}/stylists")
    List<ReviewService.StylistOption> stylists(@PathVariable UUID id) {
        return review.stylistsToAdd(id);
    }

    @PutMapping("/api/admin/match-requests/{id}/order")
    ReviewService.Review reorder(@PathVariable UUID id, @Valid @RequestBody Order order) {
        return review.reorder(id, order.matchIds());
    }

    @PutMapping("/api/admin/match-requests/{id}/matches/{matchId}")
    ReviewService.Review change(@PathVariable UUID id, @PathVariable UUID matchId, @Valid @RequestBody MatchChange change) {
        return review.update(id, matchId, change.hidden(), change.note());
    }

    @PostMapping("/api/admin/match-requests/{id}/matches")
    @ResponseStatus(HttpStatus.CREATED)
    ReviewService.Review add(@PathVariable UUID id, @Valid @RequestBody AddStylist add) {
        return review.add(id, add.orgId(), add.note());
    }

    @PostMapping("/api/admin/match-requests/{id}/publish")
    ReviewService.Review publish(@PathVariable UUID id) {
        return review.publish(id, currentUser.id());
    }

    /** Requests, matches, publishes, clicks and bookings by the client's hair type (CLAUDE.md: measure fairness). */
    @GetMapping("/api/admin/match-reports/fairness")
    ReviewService.FairnessReport fairness(@RequestParam(required = false) @Size(max = 64) String market,
                                          @RequestParam(defaultValue = "90") @Min(1) @Max(3650) int days) {
        return review.fairness(market == null || market.isBlank() ? defaultMarket : market, days);
    }
}
