package com.lookmate.app.notify;

import com.lookmate.app.notify.EmailOutbox.Template;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The emails a stylist gets about their own work on Combpatible: the weekly digest (tracking, docs/tracking.md). Sent to
 * the member's login email. Clients appear by first name only, never their contact details or photos. Queued in the
 * caller's transaction.
 */
@Component
public class StylistEmails {

    private final Notifier notifier;
    private final EmailProperties properties;

    StylistEmails(Notifier notifier, EmailProperties properties) {
        this.notifier = notifier;
        this.properties = properties;
    }

    /**
     * One client who tapped Book on the stylist last week. Paths are app paths (e.g. {@code /digest/<token>?type=BOOKED});
     * {@code booked} means a booking with this stylist is already recorded for the request, so only "Rebooked" is offered.
     */
    public record DigestClient(String firstName, String bookedPath, String rebookedPath, boolean booked) {
    }

    /**
     * The week's digest for one stylist.
     *
     * @param clientsShown       distinct clients who saw the stylist last week (on their list, or picked from the directory)
     * @param tapped             the clients who tapped Book, in the order to list them
     * @param statsLine          one line of the stylist's numbers
     * @param statusConfirmPath  where to confirm "taking new clients", when that's due; null leaves the line out
     */
    public record WeeklyDigest(String stylistName, LocalDate weekStart, long clientsShown, List<DigestClient> tapped,
                               String statsLine, String statsPath, String settingsPath, String unsubscribePath,
                               String statusConfirmPath) {
    }

    static final String SUBJECT_PREFIX = "Your week on Combpatible";

    /**
     * Queues the digest for one member of the org. Once per member per digest ({@code digestId}): a repeat is a no-op
     * and returns false.
     */
    public boolean weeklyDigest(UUID digestId, UUID orgId, UUID userId, WeeklyDigest digest, Instant now) {
        return notifier.queue("stylist-digest:" + digestId + ":" + userId, Template.STYLIST_DIGEST, userId, orgId, digestId,
                render(digest), now, now);
    }

    EmailContent render(WeeklyDigest d) {
        int tapped = d.tapped().size();
        String subject = SUBJECT_PREFIX + ": " + clients(d.clientsShown()) + ", " + tapped + " tapped Book";
        StringBuilder html = new StringBuilder();
        StringBuilder text = new StringBuilder();
        html.append("<!doctype html><html><body style=\"font-family:-apple-system,Segoe UI,Helvetica,Arial,sans-serif;"
                + "font-size:15px;line-height:1.5;color:#1a1a1a;max-width:560px;margin:0 auto;padding:16px\">");

        paragraph(html, text, greeting(d.stylistName()));
        String headline = "Last week Combpatible showed you to " + clients(d.clientsShown()) + "; "
                + (tapped == 0 ? "none tapped Book yet." : tapped + " tapped Book: " + names(d.tapped()) + ".");
        paragraph(html, text, headline);

        if (tapped > 0) {
            paragraph(html, text, "Did they book with you? One tap records it, once you've signed in to Combpatible.");
            html.append("<table role=\"presentation\" style=\"border-collapse:collapse;margin:8px 0 16px\">");
            for (DigestClient c : d.tapped()) {
                String name = firstName(c.firstName());
                html.append("<tr><td style=\"padding:6px 12px 6px 0;font-weight:600\">").append(EmailContent.escape(name)).append("</td>")
                        .append("<td style=\"padding:6px 0\">");
                text.append(name).append(":\n");
                if (c.booked()) {
                    html.append("<span style=\"color:#555;margin-right:8px\">Booking recorded</span>");
                    text.append("  Booking recorded\n");
                } else {
                    button(html, "Booked", properties.link(c.bookedPath()));
                    text.append("  Booked: ").append(properties.link(c.bookedPath())).append("\n");
                }
                button(html, "Rebooked", properties.link(c.rebookedPath()));
                text.append("  Rebooked: ").append(properties.link(c.rebookedPath())).append("\n");
                html.append("</td></tr>");
            }
            html.append("</table>");
            text.append("\n");
        }

        if (d.statusConfirmPath() != null) {
            html.append("<p>Still taking new clients? <a href=\"").append(EmailContent.escape(properties.link(d.statusConfirmPath())))
                    .append("\" style=\"color:#7a1f2b\">Confirm</a></p>");
            text.append("Still taking new clients? Confirm: ").append(properties.link(d.statusConfirmPath())).append("\n\n");
        }

        html.append("<p>").append(EmailContent.escape(d.statsLine())).append(" <a href=\"")
                .append(EmailContent.escape(properties.link(d.statsPath()))).append("\" style=\"color:#7a1f2b\">Your numbers</a></p>");
        text.append(d.statsLine()).append("\nYour numbers: ").append(properties.link(d.statsPath())).append("\n\n");

        String footer = "You get this email on Mondays because you're a live stylist on Combpatible. The Booked and Rebooked "
                + "links work for 14 days, once each, and only after you sign in. You can undo a booking on your bookings page.";
        html.append("<p style=\"font-size:12px;color:#666;border-top:1px solid #ddd;padding-top:12px;margin-top:24px\">")
                .append(EmailContent.escape(footer)).append(" <a href=\"").append(EmailContent.escape(properties.link(d.unsubscribePath())))
                .append("\" style=\"color:#666\">Unsubscribe</a> or <a href=\"").append(EmailContent.escape(properties.link(d.settingsPath())))
                .append("\" style=\"color:#666\">change it in your settings</a>.</p></body></html>");
        text.append("--\n").append(footer).append("\nUnsubscribe: ").append(properties.link(d.unsubscribePath()))
                .append("\nSettings: ").append(properties.link(d.settingsPath())).append("\n");
        return new EmailContent(subject, html.toString(), text.toString());
    }

    private static void paragraph(StringBuilder html, StringBuilder text, String p) {
        html.append("<p>").append(EmailContent.escape(p)).append("</p>");
        text.append(p).append("\n\n");
    }

    private static void button(StringBuilder html, String label, String link) {
        html.append("<a href=\"").append(EmailContent.escape(link)).append("\" style=\"display:inline-block;padding:6px 14px;"
                + "margin:2px 8px 2px 0;border:1px solid #7a1f2b;border-radius:999px;color:#7a1f2b;text-decoration:none\">")
                .append(EmailContent.escape(label)).append("</a>");
    }

    static String clients(long n) {
        return n == 1 ? "1 client" : n + " clients";
    }

    /** "Maya", "Maya and Dana", "Maya, Dana and Sam". */
    static String names(List<DigestClient> clients) {
        List<String> names = new ArrayList<>();
        clients.forEach(c -> names.add(firstName(c.firstName())));
        if (names.size() == 1) {
            return names.getFirst();
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.getLast();
    }

    private static String firstName(String name) {
        return name == null || name.isBlank() ? "A client" : name.strip();
    }

    private static String greeting(String stylistName) {
        return stylistName == null || stylistName.isBlank() ? "Hi," : "Hi " + stylistName.strip() + ",";
    }
}
