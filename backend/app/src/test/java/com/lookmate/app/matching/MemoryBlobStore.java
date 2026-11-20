package com.lookmate.app.matching;

import com.lookmate.app.storage.BlobStore;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Photos in memory, so matching's tests need no object storage. Signed links are fake but well-formed. */
class MemoryBlobStore implements BlobStore {

    final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public PresignedUpload presignUpload(String bucket, String key, String contentType, long contentLength) {
        return new PresignedUpload(URI.create("https://storage.test/" + bucket + "/" + key + "?put"), "PUT", Map.of(), Instant.now().plusSeconds(600));
    }

    @Override
    public Optional<StoredObject> head(String bucket, String key) {
        return Optional.ofNullable(objects.get(key)).map(b -> new StoredObject(b.length, "image/jpeg"));
    }

    @Override
    public byte[] read(String bucket, String key) {
        byte[] b = objects.get(key);
        if (b == null) {
            throw new IllegalStateException("no such object");
        }
        return b;
    }

    @Override
    public boolean putIfAbsent(String bucket, String key, byte[] bytes, String contentType) {
        return objects.putIfAbsent(key, bytes) == null;
    }

    @Override
    public void replace(String bucket, String key, byte[] bytes, String contentType) {
        objects.put(key, bytes);
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
        return URI.create("https://storage.test/" + bucket + "/" + key + "?signed");
    }
}
