package com.lookmate.app.notify;

import com.lookmate.app.notify.EmailOutbox.Status;
import com.lookmate.app.notify.EmailOutbox.Template;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Emails to clients, who have no account: the address comes from the caller (the client's intake answers), not from
 * the directory. Queued in the caller's transaction, like {@code Notifier}; stored as skipped when there is no address
 * or sending is off.
 */
@Component
public class ClientNotifier {

    private static final Logger log = LoggerFactory.getLogger(ClientNotifier.class);

    private final EmailOutbox outbox;
    private final EmailProperties properties;

    ClientNotifier(EmailOutbox outbox, EmailProperties properties) {
        this.outbox = outbox;
        this.properties = properties;
    }

    /**
     * "Your matches are ready", with the client's link to their ranked list ({@code /m/<token>}). Once per request:
     * returns false if it was already queued.
     */
    public boolean matchesReady(UUID matchRequestId, String email, String firstName, String linkToken, Instant now) {
        String to = email == null || email.isBlank() ? null : email.strip();
        String hello = firstName == null || firstName.isBlank() ? "Hi," : "Hi " + firstName.strip() + ",";
        EmailContent content = EmailContent.of("Your stylist matches are ready",
                List.of(hello, "A stylist on our team has reviewed your look and picked the stylists whose work fits it best. "
                        + "Each one shows the photos from their portfolio that earned the match.",
                        "These are suggestions: you can book any stylist you like."),
                "See your matches", properties.link("/m/" + linkToken),
                "You're getting this because you asked LookMate to find a stylist for a look. Anyone with this link can see "
                        + "your matches, so please don't share it.");
        Status status = Status.PENDING;
        String reason = null;
        if (to == null) {
            status = Status.SKIPPED;
            reason = Notifier.NO_EMAIL;
        } else if (!properties.enabled()) {
            status = Status.SKIPPED;
            reason = Notifier.SENDING_OFF;
        }
        String key = "matches-ready:" + matchRequestId;
        boolean queued = outbox.queue(new EmailOutbox.New(key, Template.MATCHES_READY, null, to, null, matchRequestId, content, status,
                reason, now, now));
        if (queued) {
            log.info("Email {} {}{}", key, status, reason == null ? "" : " (" + reason + ")");
        }
        return queued;
    }
}
