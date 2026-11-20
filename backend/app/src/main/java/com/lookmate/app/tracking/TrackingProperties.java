package com.lookmate.app.tracking;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param timeZone the zone days and weeks are counted in: "once per day" for VIEWED, the weekly metrics, and the dates
 *                 the admin filters by. One zone for now (Columbus); per-market zones when a second time zone arrives.
 * @param clickDedupeWindow a second "Book" click on the same match within this long is the same click
 */
@ConfigurationProperties("lookmate.tracking")
record TrackingProperties(ZoneId timeZone, Duration clickDedupeWindow) {

    TrackingProperties {
        if (timeZone == null) {
            timeZone = ZoneId.of("America/New_York");
        }
        if (clickDedupeWindow == null) {
            clickDedupeWindow = Duration.ofSeconds(30);
        }
    }
}
