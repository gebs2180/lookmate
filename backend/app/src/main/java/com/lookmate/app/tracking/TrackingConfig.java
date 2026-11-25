package com.lookmate.app.tracking;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({TrackingProperties.class, DigestProperties.class})
class TrackingConfig {
}
