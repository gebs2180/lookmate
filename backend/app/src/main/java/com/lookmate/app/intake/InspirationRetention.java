package com.lookmate.app.intake;

import com.lookmate.app.storage.BlobStore;
import com.lookmate.app.storage.StorageProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deletes a client's inspiration photo {@code lookmate.retention.inspiration-photo-days} after the request (30, a
 * default to confirm; CLAUDE.md, "Client photos are private"). Everything under the request's storage folder goes, then
 * {@code match_request.inspiration_deleted_at} is set; storage first, so a failure in between is retried next run.
 * The request, its embedding and its matches stay (docs/data-retention.md). Photos never confirmed are deleted too.
 */
@Component
public class InspirationRetention {

    private static final Logger log = LoggerFactory.getLogger(InspirationRetention.class);
    private static final int BATCH = 200;

    private final MatchRequestRows rows;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final Duration keep;

    InspirationRetention(MatchRequestRows rows, BlobStore blobs, StorageProperties storage,
                         @Value("${lookmate.retention.inspiration-photo-days:30}") int days) {
        this.rows = rows;
        this.blobs = blobs;
        this.storage = storage;
        this.keep = Duration.of(days, ChronoUnit.DAYS);
    }

    /** How many days a photo is kept: the client's page promises the date this gives. */
    public int days() {
        return (int) keep.toDays();
    }

    /** Returns how many requests had their photo deleted. */
    public int run(Instant now) {
        Instant cutoff = now.minus(keep);
        int deleted = 0;
        while (true) {
            List<MatchRequestRows.Row> due = rows.inspirationDue(cutoff, BATCH);
            for (MatchRequestRows.Row row : due) {
                blobs.deletePrefix(storage.photoBucket(), "intake/" + row.id() + "/");
                rows.setInspirationDeleted(row.id(), now);
                deleted++;
            }
            if (due.size() < BATCH) {
                break;
            }
        }
        log.info("Retention: deleted the inspiration photos of {} request(s) older than {} days", deleted, keep.toDays());
        return deleted;
    }
}
