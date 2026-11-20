package com.lookmate.app.intake;

import com.lookmate.app.storage.BlobStore;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Object storage in memory, for flow tests that run without MinIO. Uploads are simulated with {@link #upload}. */
class InMemoryBlobStore implements BlobStore {

    record Stored(byte[] bytes, String contentType) {
    }

    final Map<String, Stored> objects = new ConcurrentHashMap<>();

    void upload(String bucket, String key, byte[] bytes, String contentType) {
        objects.put(bucket + "/" + key, new Stored(bytes, contentType));
    }

    Optional<byte[]> bytes(String bucket, String key) {
        return Optional.ofNullable(objects.get(bucket + "/" + key)).map(Stored::bytes);
    }

    @Override
    public PresignedUpload presignUpload(String bucket, String key, String contentType, long contentLength) {
        return new PresignedUpload(URI.create("https://storage.test/" + bucket + "/" + key + "?signed"), "PUT",
                Map.of("Content-Type", contentType, "If-None-Match", "*"), Instant.now().plusSeconds(600));
    }

    @Override
    public Optional<StoredObject> head(String bucket, String key) {
        return Optional.ofNullable(objects.get(bucket + "/" + key)).map(s -> new StoredObject(s.bytes().length, s.contentType()));
    }

    @Override
    public byte[] read(String bucket, String key) {
        return objects.get(bucket + "/" + key).bytes();
    }

    @Override
    public boolean putIfAbsent(String bucket, String key, byte[] bytes, String contentType) {
        return objects.putIfAbsent(bucket + "/" + key, new Stored(bytes, contentType)) == null;
    }

    @Override
    public void replace(String bucket, String key, byte[] bytes, String contentType) {
        objects.put(bucket + "/" + key, new Stored(bytes, contentType));
    }

    @Override
    public int deletePrefix(String bucket, String prefix) {
        int[] n = {0};
        objects.keySet().removeIf(k -> k.startsWith(bucket + "/" + prefix) && ++n[0] > 0);
        return n[0];
    }

    @Override
    public void delete(String bucket, String key) {
        objects.remove(bucket + "/" + key);
    }

    @Override
    public URI presignDownload(String bucket, String key) {
        return URI.create("https://storage.test/" + bucket + "/" + key + "?view");
    }
}
