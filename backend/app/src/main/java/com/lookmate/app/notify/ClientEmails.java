package com.lookmate.app.notify;

import com.lookmate.app.notify.EmailOutbox.Template;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The emails a client gets (intake). Clients have no account, so these go to the address they typed in the quiz. Each
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

    /** "We got your request": the link to follow it. {@code linkPath} is the app path, e.g. {@code /m/<token>}. */
    public void requestReceived(UUID matchRequestId, String email, String firstName, String linkPath, int photoKeptDays, Instant now) {
        EmailContent content = EmailContent.of("We got your request",
                List.of(greeting(firstName),
                        "Thanks for telling us about the look you want. A Combpatible stylist is matching you with stylists whose own "
                                + "work looks like it, on hair like yours. We'll email you when your matches are ready, usually within a day or two.",
                        "Your inspiration photo is private: only our team sees it, and we delete it after " + photoKeptDays + " days."),
                "See your request", properties.link(linkPath), FOOTER);
        notifier.queueToAddress("match-request-received:" + matchRequestId, Template.MATCH_REQUEST_RECEIVED, email, matchRequestId,
                content, now);
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

    /** The client has seen their matches: a "matches ready" email that hasn't gone yet is cancelled. Returns whether one was. */
    public boolean cancelMatchesReady(UUID matchRequestId, Instant now) {
        return notifier.cancelPending(matchRequestId, Template.MATCHES_READY, VIEWED_FIRST, now) > 0;
    }

    private static String greeting(String firstName) {
        return firstName == null || firstName.isBlank() ? "Hi," : "Hi " + firstName.strip() + ",";
    }
}
