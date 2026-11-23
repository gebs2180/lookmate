package com.lookmate.app.stylists;

import com.lookmate.app.storage.BlobStore;
import com.lookmate.app.storage.StorageProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes portfolio uploads that were never confirmed (review 2026-11-22, finding 2). Metadata is stripped only at
 * confirm, so a stylist who uploads and closes the tab leaves an original with its GPS and device EXIF in storage.
 * A row still {@code PENDING_UPLOAD} after the upload link's lifetime plus an hour goes to DELETED and its object is
 * deleted. Storage is deleted after the row commits, as {@link PhotoService#delete} does; a failure is logged and the
 * object is gone with the next stylist-folder cleanup, since nothing links to it.
 */
@Component
class PendingUploadSweep {

    private static final Logger log = LoggerFactory.getLogger(PendingUploadSweep.class);

    /** Added to the upload link's lifetime, so a slow upload and its confirm are never swept from under the stylist. */
    static final Duration GRACE = Duration.ofHours(1);
    private static final int BATCH = 100;

    private final PhotoRepository photos;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final TransactionTemplate transaction;

    PendingUploadSweep(PhotoRepository photos, BlobStore blobs, StorageProperties storage, PlatformTransactionManager transactions) {
        this.photos = photos;
        this.blobs = blobs;
        this.storage = storage;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Returns how many abandoned uploads were deleted. */
    int run(Instant now) {
        Instant cutoff = now.minus(storage.uploadUrlTtl()).minus(GRACE);
        int deleted = 0;
        while (true) {
            List<Photo> due = photos.findByStatusAndCreatedAtBefore(Photo.Status.PENDING_UPLOAD, cutoff, PageRequest.of(0, BATCH));
            int swept = 0;
            for (Photo p : due) {
                String key = sweepOne(p.getId(), p.getOrgId());
                if (key != null) {
                    swept++;
                    deleteObject(key);
                }
            }
            deleted += swept;
            // A batch that swept nothing (every row was confirmed meanwhile) would repeat itself: stop.
            if (due.size() < BATCH || swept == 0) {
                break;
            }
        }
        if (deleted > 0) {
            log.info("Deleted {} portfolio upload(s) never confirmed within {}", deleted, storage.uploadUrlTtl().plus(GRACE));
        }
        return deleted;
    }

    /** Marks the row DELETED if it's still pending (a confirm may have got there first); returns its object key if so. */
    private String sweepOne(UUID id, UUID orgId) {
        return transaction.execute(status -> photos.findLocked(id, orgId)
                .filter(p -> p.getStatus() == Photo.Status.PENDING_UPLOAD)
                .map(p -> {
                    p.delete();
                    return p.getObjectKey();
                }).orElse(null));
    }

    private void deleteObject(String key) {
        try {
            blobs.delete(storage.photoBucket(), key);
        } catch (RuntimeException e) {
            log.error("Could not delete {} from storage after sweeping its abandoned upload", key, e);
        }
    }
}
