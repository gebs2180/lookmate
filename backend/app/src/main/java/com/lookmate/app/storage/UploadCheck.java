package com.lookmate.app.storage;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The confirm step of a photo upload, written once for portfolio photos and client inspiration photos (review
 * 2026-11-22, finding 39): the size check, the header check, taking the location and device metadata out, re-checking
 * what's left, and the hash. The hash is of the stripped bytes, so the same photo with different EXIF is the same photo.
 *
 * <p>This reads and decodes a full-size photo, so callers run it inside {@link PhotoDecodes#withTurn} and, if they use a
 * transaction, take the turn first. It deletes nothing: on a refusal the caller decides what happens to the object.
 */
@Component
public class UploadCheck {

    /** Why an upload can't be used. */
    public enum Refusal {
        /** Larger than {@link StorageProperties#maxUploadBytes()}. */
        TOO_LARGE,
        /** Not the size the upload slot was made for. */
        WRONG_SIZE,
        /** Not a JPEG or PNG within the pixel limits. */
        NOT_AN_IMAGE,
        /** A JPEG or PNG, but not the format the slot declared. */
        WRONG_FORMAT,
        /** The metadata couldn't be taken out, so the photo isn't kept. */
        UNSTRIPPABLE
    }

    /**
     * @param refusal null when the photo is kept, and then the rest is set
     * @param format  "jpeg" or "png"
     * @param size    bytes now stored (after stripping)
     * @param sha256  hex hash of the stored bytes
     */
    public record Result(Refusal refusal, String format, int width, int height, long size, String sha256) {

        public boolean accepted() {
            return refusal == null;
        }

        /** The Content-Type of the stored object, e.g. {@code image/jpeg}. */
        public String contentType() {
            return "image/" + format;
        }

        static Result refused(Refusal why) {
            return new Result(why, null, 0, 0, 0, null);
        }
    }

    private final BlobStore blobs;
    private final StorageProperties storage;

    UploadCheck(BlobStore blobs, StorageProperties storage) {
        this.blobs = blobs;
        this.storage = storage;
    }

    /**
     * Checks the object at {@code key} and, if it's a usable photo, overwrites it with its metadata-free copy.
     *
     * @param declaredSize  the size the upload slot was made for, or null to accept any size up to the limit
     * @param declaredFormat the format the slot declared ("jpeg" or "png"), or null to accept either
     * @return empty if nothing has been uploaded to {@code key}
     */
    public Optional<Result> check(String key, Long declaredSize, String declaredFormat) {
        String bucket = storage.photoBucket();
        Optional<BlobStore.StoredObject> stored = blobs.head(bucket, key);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        long size = stored.get().size();
        if (size > storage.maxUploadBytes()) {
            return Optional.of(Result.refused(Refusal.TOO_LARGE));
        }
        if (declaredSize != null && size != declaredSize) {
            return Optional.of(Result.refused(Refusal.WRONG_SIZE));
        }
        byte[] bytes = blobs.read(bucket, key);
        Optional<ImageGuard.Header> header = ImageGuard.safeHeader(bytes);
        if (header.isEmpty()) {
            return Optional.of(Result.refused(Refusal.NOT_AN_IMAGE));
        }
        if (declaredFormat != null && !declaredFormat.equals(header.get().format())) {
            return Optional.of(Result.refused(Refusal.WRONG_FORMAT));
        }
        byte[] clean = PhotoMetadata.strip(bytes);
        if (clean == null || ImageGuard.safeHeader(clean).isEmpty()) {
            return Optional.of(Result.refused(Refusal.UNSTRIPPABLE));
        }
        String contentType = "image/" + header.get().format();
        if (!Arrays.equals(clean, bytes)) {
            blobs.replace(bucket, key, clean, contentType);
        }
        return Optional.of(new Result(null, header.get().format(), header.get().width(), header.get().height(),
                clean.length, sha256(clean)));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
