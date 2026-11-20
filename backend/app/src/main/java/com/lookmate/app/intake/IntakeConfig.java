package com.lookmate.app.intake;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Intake's settings. Its public routes are rate-limited with the others, by {@code api/PublicRateLimitFilter}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IntakeProperties.class)
class IntakeConfig {
}
