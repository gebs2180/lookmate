package com.lookmate.app.tracking;

import java.time.Duration;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param timeZone the zone days and weeks are counted in: "once per day" for VIEWED, the weekly metrics, and the dates
 *                 the admin filters by. One zone for now (Columbus); per-market zones when a second time zone arrives.
 * @param clickDedupeWindow a second "Book" click on the same match within this long is the same click
 * @param maxClicksPerRequestPerDay at most this many clicks are counted per client link per day (in {@code timeZone});
 *                                  more still redirect, but a script on one link can't skew the metrics
 */
@ConfigurationProperties("lookmate.tracking")
record TrackingProperties(ZoneId timeZone, Duration clickDedupeWindow, Integer maxClicksPerRequestPerDay) {

    TrackingProperties {
        if (timeZone == null) {
            timeZone = ZoneId.of("America/New_York");
        }
        if (clickDedupeWindow == null) {
            clickDedupeWindow = Duration.ofSeconds(30);
        }
        if (maxClicksPerRequestPerDay == null) {
            maxClicksPerRequestPerDay = 20;
        }
    }
}
