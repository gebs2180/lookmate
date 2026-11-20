package com.lookmate.app.matching;

import com.lookmate.app.intake.ClientMatch;
import com.lookmate.app.intake.ClientMatchList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Intake's extension point for the client's page ({@code /m/<token>}): the published matches. */
@Component
class MatchListForClients implements ClientMatchList {

    private final ReviewService review;

    MatchListForClients(ReviewService review) {
        this.review = review;
    }

    @Override
    public List<ClientMatch> forClient(UUID matchRequestId) {
        return review.forClient(matchRequestId);
    }
}
