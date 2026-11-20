package com.lookmate.app.intake;

import java.util.List;
import java.util.UUID;

/**
 * One stylist on a client's match list. {@code photoUrls} are short-lived presigned links to the portfolio photos that
 * earned the match. There is no booking URL here: the client page builds tracking's redirect
 * ({@code /api/public/book/{token}/{matchId}}) from its own token, which the server only stores hashed. {@code orgId} is
 * the stylist's public id (the directory's too, so the page can mark recommended stylists there); {@code demo} marks a
 * made-up stylist, shown as "Sample stylist".
 */
public record ClientMatch(UUID matchId, UUID orgId, int rank, String stylistName, String locationName, String reason,
                          List<String> photoUrls, String instagram, boolean demo) {
}
