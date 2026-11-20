package com.lookmate.app.matching;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * The client's ranked list, through their link token (no account). Shows only what the reviewer published: matches
 * with a final rank, not hidden, of stylists still live. The token is 256 random bits, checked by its hash; a wrong
 * one is a 404 like a missing one.
 */
@RestController
class PublicMatchController {

    private final ReviewService review;

    PublicMatchController(ReviewService review) {
        this.review = review;
    }

    @GetMapping("/api/public/match-requests/{token}/matches")
    ReviewService.ClientMatches matches(@PathVariable String token) {
        if (token.length() < 20 || token.length() > 128) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return review.forClient(token);
    }
}
