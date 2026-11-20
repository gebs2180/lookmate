package com.lookmate.app.matching;

import com.lookmate.app.intake.MatchRequests;
import com.lookmate.app.intake.MatchRequests.Status;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes requests from intake ({@link MatchRequests#claimNew}: NEW ones with a confirmed photo, which it sets MATCHING
 * with {@code SKIP LOCKED}), embeds the inspiration photo, filters candidates in SQL
 * ({@link CandidateQueries#similarities}), scores them in Java ({@link Scoring}), writes the top stylists as
 * {@code match} rows, then publishes them to the client through intake's {@link MatchRequests#publish} (the system
 * ranking is the final ranking). With {@code lookmate.matching.review-before-publish} on, the request goes to
 * IN_REVIEW for the admin instead. A request with no candidates is published empty
 * ({@link MatchRequests#publishWithoutMatches}, keeping the reason for the monitor): the client is pointed at the
 * directory, and the admin can add stylists afterwards. With review on, it goes to IN_REVIEW with the reason.
 *
 * <p>If the photo can't be embedded, the request stays MATCHING with a reason, and intake hands it back after
 * {@code lookmate.intake.matching-stuck-after}; after {@code max-attempts} it becomes FAILED.
 */
@Component
class Ranker {

    static final String NO_CANDIDATES = "No stylist has a compatible portfolio photo for this look yet";
    static final String NOT_EMBEDDED = "The inspiration photo could not be read for matching";

    private static final Logger log = LoggerFactory.getLogger(Ranker.class);

    private final MatchRequests requests;
    private final EmbeddingWorker worker;
    private final CandidateQueries queries;
    private final MatchStore matches;
    private final EmbeddingProvider provider;
    private final MatchingProperties.Ranking settings;
    private final boolean reviewBeforePublish;
    private final TransactionTemplate tx;
    private final Clock clock;

    Ranker(MatchRequests requests, EmbeddingWorker worker, CandidateQueries queries, MatchStore matches, EmbeddingProvider provider,
           MatchingProperties properties, TransactionTemplate tx, Optional<Clock> clock) {
        this.requests = requests;
        this.worker = worker;
        this.queries = queries;
        this.matches = matches;
        this.provider = provider;
        this.settings = properties.ranking();
        this.reviewBeforePublish = properties.reviewBeforePublish();
        this.tx = tx;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /** Claims up to {@code limit} requests and matches each. Returns how many it claimed. */
    int rankBatch(int limit) {
        List<MatchRequests.Request> claimed = requests.claimNew(limit);
        for (MatchRequests.Request request : claimed) {
            try {
                // Embedding runs outside a transaction (a slow provider call holds no locks); ranking runs in one.
                if (inspirationEmbedded(request)) {
                    tx.executeWithoutResult(s -> rank(request));
                }
            } catch (RuntimeException e) {
                // Left MATCHING: intake hands it back once it's stuck.
                log.error("Matching request {} failed", request.id(), e);
            }
        }
        return claimed.size();
    }

    /** Embeds the photo if needed; on failure, records why on the request and returns false. */
    private boolean inspirationEmbedded(MatchRequests.Request request) {
        if (worker.hasInspiration(request.id())) {
            return true;
        }
        int attempts = worker.embedInspiration(request.id());
        if (attempts == 0) {
            return true;
        }
        boolean giveUp = attempts >= worker.maxAttempts() || request.inspirationDeleted();
        requests.markStatus(request.id(), giveUp ? Status.FAILED : Status.MATCHING,
                giveUp ? NOT_EMBEDDED : NOT_EMBEDDED + " (attempt " + attempts + "); trying again later");
        return false;
    }

    private void rank(MatchRequests.Request request) {
        Instant now = clock.instant();
        HairType hair = HairType.parse(request.hairType());
        Scoring.Settings s = settings.settings();
        List<Scoring.PhotoMatch> photos = queries.similarities(request.id(), provider.model(), request.market(), request.service(),
                s.hair().compatible(hair));
        List<Scoring.Ranked> ranked = Scoring.rank(hair, photos, s);
        int rank = 0;
        for (Scoring.Ranked r : ranked) {
            matches.insertSystem(request.id(), r.orgId(), r.score(), Reasons.forRanked(r, request.service(), hair), r.evidence(), ++rank, now);
        }
        if (reviewBeforePublish) {
            requests.markStatus(request.id(), Status.IN_REVIEW, ranked.isEmpty() ? NO_CANDIDATES : null);
        } else if (ranked.isEmpty()) {
            // Published empty at once (decided 2026-11-20): the client is pointed at the directory; the reason stays for the monitor.
            requests.publishWithoutMatches(request.id(), NO_CANDIDATES);
        } else {
            requests.publish(request.id(), null); // nobody reviewed it: reviewed_by stays null
        }
        log.info("Ranked match request {}: {} candidate photos, {} stylists, {}", request.id(), photos.size(), ranked.size(),
                reviewBeforePublish ? "in review" : ranked.isEmpty() ? "published empty" : "published");
    }
}
