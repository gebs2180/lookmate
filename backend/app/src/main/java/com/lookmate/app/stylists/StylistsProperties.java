package com.lookmate.app.stylists;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param minTaggedPhotosToPublish READY photos with a service and hair type a profile needs before it can go LIVE
 * @param newClientsConfirmAfter   how long after the new-client status was last set or confirmed the stylist's
 *                                 dashboard asks "still accurate?"
 */
@ConfigurationProperties("lookmate.stylists")
record StylistsProperties(
        @DefaultValue("3") int minTaggedPhotosToPublish,
        @DefaultValue("30d") Duration newClientsConfirmAfter) {
}
