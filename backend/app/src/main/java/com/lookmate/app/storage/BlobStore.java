package com.lookmate.app.storage;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Object storage port. Clients upload straight to storage with a presigned PUT, and objects are written once, with one
 * exception: {@link #replace}, which swaps an uploaded photo for its copy without location and device metadata
 * ({@link UploadCheck}). Nothing is deleted except a photo the stylist deleted, an upload that failed its check, or a
 * client's inspiration photo when retention runs ({@link #delete}, {@link #deletePrefix}).
 */
public interface BlobStore {

    /**
     * A presigned PUT the client uses to upload directly to storage. The signature binds the content
     * type and exact length, and {@code If-None-Match: *} so an existing object cannot be overwritten.
     */
    PresignedUpload presignUpload(String bucket, String key, String contentType, long contentLength);

    /** Size and type of a stored object, or empty if nothing has been uploaded. */
    Optional<StoredObject> head(String bucket, String key);

    byte[] read(String bucket, String key);

    /**
     * Overwrites an existing object. Only for taking the location and device metadata out of an uploaded photo
     * (privacy review 2026-11-12), with the same pixels: everything else is written once.
     */
    void replace(String bucket, String key, byte[] bytes, String contentType);

    /**
     * Deletes every object whose key starts with {@code prefix}, e.g. a client's {@code intake/<request>/} folder when
     * retention runs.
     * In a versioned bucket this leaves a delete marker over each object. Returns how many objects it deleted.
     */
    int deletePrefix(String bucket, String prefix);

    /** Deletes one object; nothing happens if it isn't there. For a deleted photo or an upload that failed its check. */
    void delete(String bucket, String key);

    /** A short-lived link for viewing one private object in the browser. */
    URI presignDownload(String bucket, String key);

    record PresignedUpload(URI url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    record StoredObject(long size, String contentType) {
    }
}
