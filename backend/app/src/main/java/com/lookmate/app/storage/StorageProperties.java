package com.lookmate.app.storage;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * S3-compatible object storage (MinIO locally, R2/S3 in production).
 *
 * @param endpoint       S3 API endpoint the app talks to; empty for AWS itself
 * @param publicEndpoint endpoint browsers use for presigned URLs, if different (e.g. behind a proxy)
 * @param pathStyle      path-style addressing (needed for MinIO)
 * @param photoBucket    private bucket for portfolio and inspiration photos (versioned locally; R2 has no versioning)
 * @param uploadUrlTtl   how long a presigned upload URL stays valid
 * @param downloadUrlTtl how long a presigned link for viewing a photo stays valid
 * @param maxUploadBytes largest photo accepted
 * @param maxPhotosPerStylist portfolio photos one stylist may have stored
 * @param maxPhotosPerOrgPerDay upload attempts allowed for one org in 24 hours
 * @param photoChecksAtOnce photos decoded at the same time: a 48 MP photo needs about 150 MB of heap
 * @param autoCreateBuckets create the photo bucket (versioned) at startup if missing; local dev and tests only
 * @param chunkedEncoding aws-chunked uploads from the app; Cloudflare R2 rejects them (403), so production turns this off
 */
@ConfigurationProperties("lookmate.storage")
public record StorageProperties(
        URI endpoint,
        URI publicEndpoint,
        @DefaultValue("us-east-1") String region,
        String accessKey,
        String secretKey,
        @DefaultValue("true") boolean pathStyle,
        @DefaultValue("photos") String photoBucket,
        @DefaultValue("10m") Duration uploadUrlTtl,
        @DefaultValue("15m") Duration downloadUrlTtl,
        @DefaultValue("26214400") long maxUploadBytes,
        @DefaultValue("60") int maxPhotosPerStylist,
        @DefaultValue("200") int maxPhotosPerOrgPerDay,
        @DefaultValue("2") int photoChecksAtOnce,
        @DefaultValue("false") boolean autoCreateBuckets,
        @DefaultValue("true") boolean chunkedEncoding) {

    /** Never prints the secret key. */
    @Override
    public String toString() {
        return "StorageProperties[endpoint=" + endpoint + ", region=" + region + ", accessKey=" + accessKey
                + ", secretKey=" + (secretKey == null || secretKey.isBlank() ? "unset" : "***")
                + ", photoBucket=" + photoBucket + ", maxUploadBytes=" + maxUploadBytes + "]";
    }
}
