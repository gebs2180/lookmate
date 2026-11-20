package com.lookmate.app.api;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerExceptionResolver;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitProperties.class)
class RateLimitConfig {

    /**
     * Ahead of Spring Security ({@code -100}), so a refused request costs no token parsing. Tomcat has already set the
     * remote address from X-Forwarded-For by then (a valve runs before every filter).
     */
    static final int ORDER = -110;

    @Bean
    @ConditionalOnProperty(name = "lookmate.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<PublicRateLimitFilter> publicRateLimitFilter(RateLimitProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors, ObjectMapper json) {
        var registration = new FilterRegistrationBean<>(new PublicRateLimitFilter(properties, errors, json, System::nanoTime));
        registration.addUrlPatterns("/api/*");
        registration.setOrder(ORDER);
        return registration;
    }
}
