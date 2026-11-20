package com.lookmate.app.intake;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IntakeProperties.class)
class IntakeConfig implements WebMvcConfigurer {

    private final PublicRateLimit rateLimit;

    IntakeConfig(PublicRateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // The directory (stylists) and its Book links (tracking) share this limiter: one per-IP limit for the public API.
        registry.addInterceptor(rateLimit)
                .addPathPatterns("/api/public/match-requests", "/api/public/match-requests/**", "/api/public/intake/**")
                .addPathPatterns(PublicRateLimit.BROWSE_PATHS);
    }
}
