package com.lookmate.app.intake;

import java.util.List;
import java.util.UUID;

/**
 * One stylist on a client's match list. {@code photoUrls} are short-lived presigned links to the portfolio photos that
 * earned the match; {@code bookUrl} is the tracking redirect to the stylist's own booking link. A proposal: the
 * matching module may add fields.
 */
public record ClientMatch(UUID matchId, int rank, String stylistName, String locationName, String reason, List<String> photoUrls,
                          String bookUrl, String instagram) {
}
