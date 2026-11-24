package com.lookmate.app.consult;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The service finder's settings (docs/modules/consult.md).
 *
 * @param partnerSlug the partner's own link, {@code /c/<slug>}: set it to the partner's slug before sharing the link
 * @param rateLimit   per-IP limits on {@code /api/public/consult/**}
 */
@ConfigurationProperties("lookmate.consult")
record ConsultProperties(@DefaultValue("partner") String partnerSlug, @DefaultValue RateLimit rateLimit) {

    /**
     * @param submitsPerHour finished finders (answers submitted) per IP per hour
     * @param readsPerMinute everything else (questions, Book and find-a-stylist clicks) per IP per minute
     */
    record RateLimit(@DefaultValue("30") int submitsPerHour, @DefaultValue("60") int readsPerMinute) {
    }
}
