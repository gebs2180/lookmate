package com.lookmate.app.notify;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Email settings, and, with sending on (NOTIFY_EMAIL_ENABLED), the Resend client and the jobs that send. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmailProperties.class)
class NotifyConfig {

    @Bean
    @ConditionalOnBooleanProperty("lookmate.notify.email.enabled")
    ResendClient resendClient(EmailProperties properties) {
        return new ResendClient(properties);
    }

    @Bean
    @ConditionalOnBooleanProperty("lookmate.notify.email.enabled")
    EmailSender emailSender(EmailOutbox outbox, ResendClient resend, EmailProperties properties) {
        return new EmailSender(outbox, resend, properties);
    }

    /** Sends due emails every poll interval. Tests call the job directly. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnBooleanProperty("lookmate.notify.email.enabled")
    static class Jobs {

        private static final Logger log = LoggerFactory.getLogger(Jobs.class);

        private final EmailSender sender;

        Jobs(EmailSender sender) {
            this.sender = sender;
        }

        @Scheduled(fixedDelayString = "${lookmate.notify.email.poll-interval:20s}", initialDelayString = "${lookmate.notify.email.poll-interval:20s}")
        void send() {
            try {
                sender.sendDue(Instant.now());
            } catch (RuntimeException e) {
                log.error("Email send pass failed", e);
            }
        }

    }
}
