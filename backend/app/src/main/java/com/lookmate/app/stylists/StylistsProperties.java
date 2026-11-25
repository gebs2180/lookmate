package com.lookmate.app.stylists;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param minTaggedPhotosToPublish READY photos with a service and hair type a profile needs before it can go LIVE
 * @param newClientsConfirmAfter   how long after the new-client status was last set or confirmed the stylist's
 *                                 dashboard asks "still accurate?"
 * @param finderOrgId              the partner stylist whose page (/s/&lt;slug&gt;) offers "Not sure what to book?", or
 *                                 null for nobody. The service finder (consult module) has no tie to a stylist org,
 *                                 so it is named here (docs/research/service-finder.md).
 * @param finderPath               where that button goes: the partner's own finder link, /c/&lt;partner-slug&gt;
 */
@ConfigurationProperties("lookmate.stylists")
record StylistsProperties(
        @DefaultValue("3") int minTaggedPhotosToPublish,
        @DefaultValue("30d") Duration newClientsConfirmAfter,
        UUID finderOrgId,
        @DefaultValue("/c/partner") String finderPath) {
}
