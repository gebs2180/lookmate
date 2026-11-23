package com.lookmate.app.stylists;

import com.lookmate.app.storage.BlobStore;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * An in-memory {@link BlobStore}, so the portfolio flows run against a real database without object storage. The
 * test plays the browser's presigned PUT with {@link #upload}. The real S3 path is covered by
 * {@link PortfolioStorageTest}.
 */
class FakeBlobStore implements BlobStore {

    record Stored(byte[] bytes, String contentType) {
    }

    final Map<String, Stored> objects = new ConcurrentHashMap<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class Config {
        @Bean
        @Primary
        FakeBlobStore fakeBlobStore() {
            return new FakeBlobStore();
        }
    }

    /** What the browser does with the presigned PUT. */
    void upload(String key, byte[] bytes, String contentType) {
        objects.put(key, new Stored(bytes, contentType));
    }

    @Override
    public PresignedUpload presignUpload(String bucket, String key, String contentType, long contentLength) {
        return new PresignedUpload(URI.create("https://storage.test/" + bucket + "/" + key + "?signed=put"), "PUT",
                Map.of("Content-Type", contentType, "If-None-Match", "*"), Instant.now().plusSeconds(600));
    }

    @Override
    public Optional<StoredObject> head(String bucket, String key) {
        return Optional.ofNullable(objects.get(key)).map(o -> new StoredObject(o.bytes().length, o.contentType()));
    }

    @Override
    public byte[] read(String bucket, String key) {
        return objects.get(key).bytes();
    }

    @Override
    public void replace(String bucket, String key, byte[] bytes, String contentType) {
        objects.put(key, new Stored(bytes, contentType));
    }

    @Override
    public int deletePrefix(String bucket, String prefix) {
        int before = objects.size();
        objects.keySet().removeIf(k -> k.startsWith(prefix));
        return before - objects.size();
    }

    @Override
    public void delete(String bucket, String key) {
        objects.remove(key);
    }

    @Override
    public URI presignDownload(String bucket, String key) {
        return URI.create("https://storage.test/" + bucket + "/" + key + "?signed=get");
    }
}
