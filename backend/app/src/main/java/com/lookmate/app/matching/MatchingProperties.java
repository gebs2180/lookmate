package com.lookmate.app.matching;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Matching v1 settings ({@code lookmate.matching}).
 *
 * @param provider      the {@link EmbeddingProvider}: only {@code fake} until one is chosen (docs/embedding-providers.md)
 * @param fakeDimension vector length of the fake provider
 * @param reviewBeforePublish off (the default, decided 2026-11-20): the ranker publishes its list to the client as soon
 *                            as it is ready and the admin monitors afterwards. On: every list waits IN_REVIEW for the
 *                            admin to publish it, as in Phase 1
 */
@ConfigurationProperties("lookmate.matching")
record MatchingProperties(@DefaultValue("fake") String provider,
                          @DefaultValue("64") int fakeDimension,
                          @DefaultValue("false") boolean reviewBeforePublish,
                          @DefaultValue Worker worker,
                          @DefaultValue Ranking ranking) {

    /**
     * The embedding and ranking job.
     *
     * @param enabled      run it on a schedule (tests call it directly)
     * @param pollInterval pause between passes
     * @param batchSize    images claimed per provider call; their rows stay locked for the call, so keep it small
     * @param maxAttempts  tries per image before it is given up on (a request then becomes FAILED)
     * @param retryBackoff wait after a failure, times the number of attempts so far
     */
    record Worker(@DefaultValue("true") boolean enabled,
                  @DefaultValue("30s") Duration pollInterval,
                  @DefaultValue("16") int batchSize,
                  @DefaultValue("5") int maxAttempts,
                  @DefaultValue("5m") Duration retryBackoff) {
    }

    /**
     * The score ({@link Scoring}). The threshold depends on the model: SigLIP-style models give lower cosine
     * similarities than CLIP for the same pair, so set it from the labelled set when the model is chosen.
     */
    record Ranking(@DefaultValue("3") int bestK,
                   @DefaultValue("0.8") double similarThreshold,
                   @DefaultValue("0.25") double countWeight,
                   @DefaultValue("10") int countCap,
                   @DefaultValue("4") int evidencePhotos,
                   @DefaultValue("5") int keep,
                   @DefaultValue("0.5") double adjacentHairTypeWeight) {

        Scoring.Settings settings() {
            return new Scoring.Settings(bestK, similarThreshold, countWeight, countCap, evidencePhotos, keep,
                    new HairCompatibility(adjacentHairTypeWeight));
        }
    }
}
