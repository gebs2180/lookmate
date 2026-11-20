package com.lookmate.app.stylists;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param minTaggedPhotosToPublish READY photos with a service and hair type a profile needs before it can go LIVE
 */
@ConfigurationProperties("lookmate.stylists")
record StylistsProperties(
        @DefaultValue("3") int minTaggedPhotosToPublish) {
}
