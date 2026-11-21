package com.lookmate.app.notify;

import com.lookmate.app.notify.EmailOutbox.Template;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The emails a client gets (intake). Clients have no account, and giving an email is optional: it's asked on their results
 * page, after the reveal (CLAUDE.md, 2026-11-20). Intake calls these only for a client who gave one. Each
 * carries the client's private link; it is stored with the email until retention clears the content
 * (docs/data-retention.md). Queued in the caller's transaction.
 */
@Component
public class ClientEmails {

    private static final String FOOTER = "You're getting this because you asked Combpatible to find you a stylist. "
            + "Keep this link to yourself: anyone with it can see your request.";

    private final Notifier notifier;
    private final EmailProperties properties;

    ClientEmails(Notifier notifier, EmailProperties properties) {
        this.notifier = notifier;
        this.properties = properties;
    }

    /** Where the client's request stood when they gave their email: the link email says what comes next. */
    public enum ListState { WAITING, READY, NO_CLOSE_MATCH }

    /**
     * The client gave (or corrected) their email on their request page: the private link to it. {@code linkPath} is the
     * app path, e.g. {@code /m/<token>}; {@code change} numbers the client's emails for this request, so a corrected
     * address gets its own.
     */
    public void listLink(UUID matchRequestId, String email, String firstName, String linkPath, ListState state, int change, Instant now) {
        EmailContent content = switch (state) {
            case WAITING -> EmailContent.of("Your Combpatible request",
                    List.of(greeting(firstName),
                            "Here's the private link to your request. We're matching you with stylists whose own work looks like your "
                                    + "photo, on hair like yours. It usually takes a few minutes, and we'll email you when your list is ready."),
                    "See your request", properties.link(linkPath), FOOTER);
            case READY -> EmailContent.of("Your stylist list",
                    List.of(greeting(firstName),
                            "Here's the private link to the stylists we picked for your look, with the work that earned each match. "
                                    + "They're suggestions: you can book any stylist you like.",
                            "We may ask how your appointment went."),
                    "See your stylists", properties.link(linkPath), FOOTER);
            case NO_CLOSE_MATCH -> EmailContent.of("We'll tell you when picks are added",
                    List.of(greeting(firstName),
                            "We haven't found a stylist whose work is a close match for your look yet. If our team adds picks to your "
                                    + "list, we'll email you. Meanwhile you can browse all our stylists and book any of them.",
                            "We may ask how your appointment went."),
                    "See your request", properties.link(linkPath), FOOTER);
        };
        notifier.queueToAddress("match-list-link:" + matchRequestId + ":" + change, Template.MATCH_LIST_LINK, email, matchRequestId,
                content, now);
    }

    /**
     * Our team added stylists to a list that was published empty, and the client had asked to hear about it: a fresh
     * link to their list. Once per request.
     */
    public void picksAdded(UUID matchRequestId, String email, String firstName, String linkPath, Instant now) {
        EmailContent content = EmailContent.of("Picks added to your stylist list",
                List.of(greeting(firstName),
                        "Our team found stylists whose work fits the look you asked for and added them to your list. They're "
                                + "suggestions: you can book any stylist you like."),
                "See your picks", properties.link(linkPath), FOOTER);
        notifier.queueToAddress("picks-added:" + matchRequestId, Template.PICKS_ADDED, email, matchRequestId, content, now);
    }

    static final String VIEWED_FIRST = "the client opened their matches before it went";

    /**
     * "Your matches are ready", with a fresh link, due at {@code sendAt} (a few minutes after publishing, so a client
     * still on their page isn't emailed). Sent once per request; {@link #cancelMatchesReady} stops it if it hasn't gone.
     */
    public void matchesReady(UUID matchRequestId, String email, String firstName, String linkPath, Instant sendAt, Instant now) {
        EmailContent content = EmailContent.of("Your stylist matches are ready",
                List.of(greeting(firstName),
                        "We've picked stylists whose portfolios look like the style you want. Each one shows the photos that "
                                + "earned the match. They're suggestions: you can book any stylist you like."),
                "See your matches", properties.link(linkPath), FOOTER);
        notifier.queueToAddress("matches-ready:" + matchRequestId, Template.MATCHES_READY, email, matchRequestId, content, sendAt, now);
    }

    /**
     * The "matches ready" email's variant for a list published empty: no stylist's portfolio is a close match yet. Links
     * to the directory through the client's private link ({@code linkPath}, e.g. {@code /m/<token>/stylists}). Same
     * timing, key and cancelling as {@link #matchesReady}: a request gets one or the other.
     */
    public void noCloseMatch(UUID matchRequestId, String email, String firstName, String linkPath, Instant sendAt, Instant now) {
        EmailContent content = EmailContent.of("About your stylist matches",
                List.of(greeting(firstName),
                        "We looked through every Combpatible stylist's portfolio and didn't find a close match for your look yet: "
                                + "nobody has shown that work on hair like yours so far.",
                        "You can still browse all our stylists and book any of them. Our team may also add picks to your list, "
                                + "so it's worth opening your link again later."),
                "Browse all stylists", properties.link(linkPath), FOOTER);
        notifier.queueToAddress("matches-ready:" + matchRequestId, Template.MATCHES_READY, email, matchRequestId, content, sendAt, now);
    }

    /** The client has seen their matches: a "matches ready" email that hasn't gone yet is cancelled. Returns whether one was. */
    public boolean cancelMatchesReady(UUID matchRequestId, Instant now) {
        return notifier.cancelPending(matchRequestId, Template.MATCHES_READY, VIEWED_FIRST, now) > 0;
    }

    private static String greeting(String firstName) {
        return firstName == null || firstName.isBlank() ? "Hi there," : "Hi " + firstName.strip() + ",";
    }
}
