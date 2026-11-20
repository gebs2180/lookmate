package com.lookmate.app.intake;

import java.util.List;
import java.util.UUID;

/**
 * One stylist on a client's match list. {@code photoUrls} are short-lived presigned links to the portfolio photos that
 * earned the match. There is no booking URL here: the client page builds tracking's redirect
 * ({@code /api/public/book/{token}/{matchId}}) from its own token, which the server only stores hashed.
 */
public record ClientMatch(UUID matchId, int rank, String stylistName, String locationName, String reason, List<String> photoUrls,
                          String instagram) {
}
