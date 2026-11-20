package com.lookmate.app.notify;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Notification emails, sent through <a href="https://resend.com/docs/api-reference/emails/send-email">Resend</a>.
 * Off by default: with sending off, every email is still stored, as skipped.
 *
 * @param from           the sender, on a domain verified in Resend
 * @param appUrl         where links in emails point
 * @param connectTimeout how long to wait for a connection
 * @param timeout        how long to wait for Resend's answer
 * @param pollInterval   how often the worker looks for emails that are due
 * @param batchSize      the most emails one pass sends
 * @param maxAttempts    tries before an email is given up on as failed
 */
@ConfigurationProperties("lookmate.notify.email")
record EmailProperties(@DefaultValue("false") boolean enabled, String apiKey,
                       @DefaultValue("Combpatible <notify@example.com>") String from,
                       @DefaultValue("https://api.resend.com") URI baseUrl,
                       @DefaultValue("http://localhost:5173") URI appUrl,
                       @DefaultValue("5s") Duration connectTimeout,
                       @DefaultValue("15s") Duration timeout,
                       @DefaultValue("20s") Duration pollInterval,
                       @DefaultValue("20") int batchSize,
                       @DefaultValue("8") int maxAttempts) {

    /** A link into the app, e.g. {@code link("/matches/abc")}. */
    String link(String path) {
        String base = appUrl.toString();
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + path;
    }

    /** Never prints the key. */
    @Override
    public String toString() {
        return "EmailProperties[enabled=" + enabled + ", apiKey=" + (apiKey == null || apiKey.isBlank() ? "unset" : "***") + ", from=" + from
                + ", baseUrl=" + baseUrl + ", appUrl=" + appUrl + ", connectTimeout=" + connectTimeout + ", timeout=" + timeout
                + ", pollInterval=" + pollInterval + ", batchSize=" + batchSize + ", maxAttempts=" + maxAttempts + "]";
    }
}
