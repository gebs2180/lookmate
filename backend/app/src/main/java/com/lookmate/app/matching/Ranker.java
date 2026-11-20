package com.lookmate.app.matching;

import com.lookmate.app.matching.CandidateQueries.RequestFacts;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Ranks requests whose inspiration photo is embedded: filters candidates in SQL ({@link CandidateQueries#similarities}),
 * scores them in Java ({@link Scoring}), writes the top stylists as {@code match} rows and moves the request to
 * IN_REVIEW. A request with no candidates goes to review too, with a reason, so the reviewer can add stylists by hand.
 */
@Component
class Ranker {

    static final String NO_CANDIDATES = "No stylist has a compatible portfolio photo for this look yet";

    private static final Logger log = LoggerFactory.getLogger(Ranker.class);

    private final CandidateQueries queries;
    private final MatchStore matches;
    private final RequestTransitions transitions;
    private final EmbeddingProvider provider;
    private final MatchingProperties.Ranking settings;
    private final TransactionTemplate tx;
    private final Clock clock;

    Ranker(CandidateQueries queries, MatchStore matches, RequestTransitions transitions, EmbeddingProvider provider,
           MatchingProperties properties, TransactionTemplate tx, Optional<Clock> clock) {
        this.queries = queries;
        this.matches = matches;
        this.transitions = transitions;
        this.provider = provider;
        this.settings = properties.ranking();
        this.tx = tx;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    /** Ranks up to {@code limit} waiting requests, each claimed with SKIP LOCKED. Returns how many it ranked. */
    int rankBatch(int limit) {
        Integer n = tx.execute(s -> {
            List<UUID> claimed = queries.claimRequestsToRank(provider.model(), limit);
            claimed.forEach(this::rank);
            return claimed.size();
        });
        return n == null ? 0 : n;
    }

    private void rank(UUID requestId) {
        Instant now = clock.instant();
        RequestFacts request = queries.request(requestId).orElseThrow();
        Scoring.Settings s = settings.settings();
        List<Scoring.PhotoMatch> photos = queries.similarities(requestId, provider.model(), request.market(), request.service(),
                s.hair().compatible(request.hairType()));
        List<Scoring.Ranked> ranked = Scoring.rank(request.hairType(), photos, s);
        int rank = 0;
        for (Scoring.Ranked r : ranked) {
            matches.insertSystem(requestId, r.orgId(), r.score(), Reasons.forRanked(r, request.service(), request.hairType()), r.evidence(),
                    ++rank, now);
        }
        transitions.toReview(requestId, ranked.isEmpty() ? NO_CANDIDATES : null, now);
        log.info("Ranked match request {}: {} candidate photos, {} stylists", requestId, photos.size(), ranked.size());
    }
}
