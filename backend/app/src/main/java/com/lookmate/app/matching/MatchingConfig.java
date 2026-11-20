package com.lookmate.app.matching;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** The embedding provider, and the job that embeds and ranks. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MatchingProperties.class)
class MatchingConfig {

    @Bean
    @ConditionalOnProperty(name = "lookmate.matching.provider", havingValue = "fake", matchIfMissing = true)
    EmbeddingProvider fakeEmbeddingProvider(MatchingProperties properties) {
        return new FakeEmbeddingProvider(properties.fakeDimension());
    }

    /** Every poll interval: embed what's missing, then rank the requests that are ready. Tests call the job directly. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "lookmate.matching.worker.enabled", havingValue = "true", matchIfMissing = true)
    static class Jobs {

        private final MatchingJob job;

        Jobs(MatchingJob job) {
            this.job = job;
        }

        @Scheduled(fixedDelayString = "${lookmate.matching.worker.poll-interval:30s}",
                initialDelayString = "${lookmate.matching.worker.poll-interval:30s}")
        void run() {
            job.runOnce();
        }
    }
}
