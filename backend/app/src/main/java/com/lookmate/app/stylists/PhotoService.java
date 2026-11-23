package com.lookmate.app.stylists;

import com.lookmate.app.api.ApiException;
import com.lookmate.app.identity.Consents;
import com.lookmate.app.identity.CurrentUser;
import com.lookmate.app.identity.OrgAccess;
import com.lookmate.app.identity.OrgType;
import com.lookmate.app.storage.BlobStore;
import com.lookmate.app.storage.PhotoDecodes;
import com.lookmate.app.storage.StorageProperties;
import com.lookmate.app.storage.UploadCheck;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Portfolio photos. The stylist asks for an upload slot, PUTs the file straight to storage, then confirms; the
 * confirm checks it really is the JPEG or PNG it claimed to be, hashes it, strips location and device metadata
 * and marks it READY. Then they tag, pair, reorder, hide or delete it.
 */
@Service
class PhotoService {

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    static final Map<String, String> ACCEPTED_TYPES = Map.of("image/jpeg", "jpeg", "image/png", "png");

    /** How long a confirm waits its turn to read a photo before the stylist is asked to try again. */
    private static final Duration CHECK_WAIT = Duration.ofSeconds(60);

    /** A pending upload this young still counts toward the photo cap: its slot may yet be used. */
    private static final Duration PENDING_COUNTS_FOR = Duration.ofHours(1);

    private static final List<Photo.Status> KEPT = List.of(Photo.Status.READY, Photo.Status.HIDDEN);

    record UploadRequest(String contentType, long sizeBytes) {
    }

    record UploadSlot(UUID photoId, URI url, String method, Map<String, String> headers, Instant expiresAt) {
    }

    /** @param url a short-lived signed link to view the photo; null while the upload is pending */
    record PhotoView(UUID id, Photo.Status status, URI url, String contentType, Integer width, Integer height,
                     ServiceType service, HairType hairType, Texture texture, HairLength length, boolean before,
                     UUID pairPhotoId, int position, Instant createdAt) {
    }

    /** @param before true if this photo is the "before" of the pair; ignored without a pair */
    record TagRequest(ServiceType service, HairType hairType, Texture texture, HairLength length, boolean before,
                      UUID pairPhotoId) {
    }

    record OrderRequest(List<UUID> photoIds) {
    }

    private final PhotoRepository photos;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final PhotoDecodes decodes;
    private final UploadCheck uploads;
    private final OrgAccess access;
    private final Consents consents;
    private final CurrentUser currentUser;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transaction;

    PhotoService(PhotoRepository photos, BlobStore blobs, StorageProperties storage, PhotoDecodes decodes, UploadCheck uploads,
                 OrgAccess access, Consents consents, CurrentUser currentUser, ApplicationEventPublisher events,
                 PlatformTransactionManager transactions) {
        this.photos = photos;
        this.blobs = blobs;
        this.storage = storage;
        this.decodes = decodes;
        this.uploads = uploads;
        this.access = access;
        this.consents = consents;
        this.currentUser = currentUser;
        this.events = events;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** READY and HIDDEN photos in the stylist's order, with signed links. */
    @Transactional(readOnly = true)
    List<PhotoView> list(UUID orgId) {
        access.require(orgId, OrgType.STYLIST);
        return photos.findByOrgIdAndStatusInOrderByPositionAscCreatedAtAsc(orgId, KEPT).stream().map(this::view).toList();
    }

    @Transactional
    UploadSlot requestUpload(UUID orgId, UploadRequest request) {
        access.require(orgId, OrgType.STYLIST);
        consents.requireAccepted(orgId);
        String contentType = request.contentType() == null ? "" : request.contentType().strip().toLowerCase();
        if (!ACCEPTED_TYPES.containsKey(contentType)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, StylistError.UNSUPPORTED_TYPE,
                    "Photos must be JPEG or PNG. On an iPhone, set Settings, Camera, Formats to Most Compatible, or pick the photo from your library.");
        }
        if (request.sizeBytes() <= 0 || request.sizeBytes() > storage.maxUploadBytes()) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, StylistError.TOO_LARGE,
                    "Photos can be up to " + storage.maxUploadBytes() / (1024 * 1024) + " MB");
        }
        Instant now = Instant.now();
        if (photos.countByOrgIdAndCreatedAtAfter(orgId, now.minus(Duration.ofDays(1))) >= storage.maxPhotosPerOrgPerDay()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, StylistError.UPLOAD_RATE,
                    "You've reached the limit of " + storage.maxPhotosPerOrgPerDay() + " uploads in a day. Try again tomorrow.");
        }
        long held = photos.countByOrgIdAndStatusIn(orgId, KEPT)
                + photos.countByOrgIdAndStatusAndCreatedAtAfter(orgId, Photo.Status.PENDING_UPLOAD, now.minus(PENDING_COUNTS_FOR));
        if (held >= storage.maxPhotosPerStylist()) {
            throw new ApiException(HttpStatus.CONFLICT, StylistError.PHOTO_LIMIT,
                    "You have " + storage.maxPhotosPerStylist() + " photos, the most a portfolio can hold. Delete one to add another.");
        }
        Photo photo = photos.save(new Photo(orgId, contentType, request.sizeBytes(), photos.maxPosition(orgId) + 1, currentUser.id()));
        BlobStore.PresignedUpload upload = blobs.presignUpload(storage.photoBucket(), photo.getObjectKey(), contentType, request.sizeBytes());
        return new UploadSlot(photo.getId(), upload.url(), upload.method(), upload.headers(), upload.expiresAt());
    }

    /**
     * Waits its turn outside the transaction, so a burst of uploads holds no database connections and only a few
     * full-size photos sit in memory at once ({@link PhotoDecodes}). Idempotent: a second confirm returns the result.
     */
    PhotoView confirm(UUID orgId, UUID photoId) {
        access.require(orgId, OrgType.STYLIST);
        consents.requireAccepted(orgId);
        Checked checked;
        try {
            checked = decodes.withTurn(CHECK_WAIT, () -> transaction.execute(status -> check(orgId, photoId)));
        } catch (PhotoDecodes.Busy e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, StylistError.BUSY,
                    "We're checking a lot of photos right now. Try again in a minute.");
        }
        if (checked.refusal() != null) {
            // Thrown after the transaction committed, so the photo stays DELETED and the upload stays gone.
            throw checked.refusal();
        }
        return checked.view();
    }

    private record Checked(PhotoView view, ApiException refusal) {
    }

    private Checked check(UUID orgId, UUID photoId) {
        Photo photo = photos.findLocked(photoId, orgId).orElseThrow(PhotoService::notFound);
        if (photo.getStatus() != Photo.Status.PENDING_UPLOAD) {
            if (photo.getStatus() == Photo.Status.DELETED) {
                throw notFound();
            }
            return new Checked(view(photo), null);
        }
        UploadCheck.Result result = uploads.check(photo.getObjectKey(), photo.getSizeBytes(), ACCEPTED_TYPES.get(photo.getContentType()))
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, StylistError.NOT_UPLOADED, "Nothing uploaded for this photo yet"));
        if (!result.accepted()) {
            return refuse(photo, StylistError.NOT_AN_IMAGE, switch (result.refusal()) {
                case WRONG_SIZE, TOO_LARGE -> "The upload wasn't the size it said it would be. Try again.";
                default -> "That file isn't a JPEG or PNG photo we can use.";
            });
        }
        if (photos.existsByOrgIdAndSha256AndStatusInAndIdNot(orgId, result.sha256(), KEPT, photo.getId())) {
            return refuse(photo, StylistError.DUPLICATE_PHOTO, "This photo is already in your portfolio.");
        }
        photo.ready(result.sha256(), result.width(), result.height(), result.size());
        events.publishEvent(new PortfolioPhotoReady(photo.getId(), orgId));
        return new Checked(view(photo), null);
    }

    /** The upload is unusable: it's deleted from storage and the row marked DELETED, then the stylist is told why. */
    private Checked refuse(Photo photo, StylistError code, String message) {
        log.info("Photo {}: refused at confirm ({})", photo.getId(), code);
        photo.delete();
        deleteObjectAfterCommit(photo.getObjectKey());
        return new Checked(null, new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, code, message));
    }

    @Transactional
    PhotoView tag(UUID orgId, UUID photoId, TagRequest request) {
        access.require(orgId, OrgType.STYLIST);
        consents.requireAccepted(orgId);
        Photo photo = photos.findLocked(photoId, orgId).orElseThrow(PhotoService::notFound);
        requireKept(photo);
        photo.tag(request.service(), request.hairType(), request.texture(), request.length());
        UUID oldPair = photo.getPairPhotoId();
        UUID newPair = request.pairPhotoId();
        if (newPair != null && newPair.equals(photo.getId())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, StylistError.INVALID_PAIR, "A photo can't be paired with itself");
        }
        if (oldPair != null && !oldPair.equals(newPair)) {
            photos.findLocked(oldPair, orgId).ifPresent(old -> old.pair(null, false));
        }
        if (newPair != null) {
            Photo partner = photos.findLocked(newPair, orgId).filter(Photo::isVisibleToOwner)
                    .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, StylistError.INVALID_PAIR, "Pick one of your own photos to pair with"));
            if (partner.getPairPhotoId() != null && !partner.getPairPhotoId().equals(photo.getId())) {
                // The partner leaves its old pair: a photo is in at most one pair.
                photos.findLocked(partner.getPairPhotoId(), orgId).ifPresent(old -> old.pair(null, false));
            }
            partner.pair(photo.getId(), !request.before());
        }
        photo.pair(newPair, request.before());
        events.publishEvent(new PortfolioPhotoTagged(photo.getId(), orgId));
        return view(photo);
    }

    @Transactional
    List<PhotoView> reorder(UUID orgId, OrderRequest request) {
        access.require(orgId, OrgType.STYLIST);
        consents.requireAccepted(orgId);
        List<Photo> current = photos.findByOrgIdAndStatusInOrderByPositionAscCreatedAtAsc(orgId, KEPT);
        List<UUID> wanted = request.photoIds() == null ? List.of() : request.photoIds();
        Set<UUID> currentIds = current.stream().map(Photo::getId).collect(Collectors.toSet());
        if (wanted.size() != current.size() || !new HashSet<>(wanted).equals(currentIds)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, StylistError.INVALID_ORDER,
                    "Your photos changed while you were ordering them. Reload and try again.");
        }
        Map<UUID, Photo> byId = current.stream().collect(Collectors.toMap(Photo::getId, Function.identity()));
        for (int i = 0; i < wanted.size(); i++) {
            byId.get(wanted.get(i)).moveTo(i);
        }
        return wanted.stream().map(byId::get).map(this::view).toList();
    }

    @Transactional
    PhotoView hide(UUID orgId, UUID photoId) {
        access.require(orgId, OrgType.STYLIST);
        Photo photo = photos.findLocked(photoId, orgId).orElseThrow(PhotoService::notFound);
        requireKept(photo);
        if (photo.getStatus() == Photo.Status.READY) {
            photo.hide();
            events.publishEvent(new PortfolioPhotoRemoved(photo.getId(), orgId));
        }
        return view(photo);
    }

    @Transactional
    PhotoView show(UUID orgId, UUID photoId) {
        access.require(orgId, OrgType.STYLIST);
        consents.requireAccepted(orgId);
        Photo photo = photos.findLocked(photoId, orgId).orElseThrow(PhotoService::notFound);
        requireKept(photo);
        if (photo.getStatus() == Photo.Status.HIDDEN) {
            photo.show();
            events.publishEvent(new PortfolioPhotoReady(photo.getId(), orgId));
        }
        return view(photo);
    }

    /**
     * Gone for good: the row is DELETED (kept, since matches may cite it) and the object is deleted from storage once
     * that's committed. No terms check: a stylist can always take their work down.
     */
    @Transactional
    void delete(UUID orgId, UUID photoId) {
        access.require(orgId, OrgType.STYLIST);
        Photo photo = photos.findLocked(photoId, orgId).orElseThrow(PhotoService::notFound);
        if (photo.getStatus() == Photo.Status.DELETED) {
            return;
        }
        boolean wasReady = photo.getStatus() == Photo.Status.READY;
        if (photo.getPairPhotoId() != null) {
            photos.findLocked(photo.getPairPhotoId(), orgId).ifPresent(partner -> partner.pair(null, false));
        }
        photo.delete();
        deleteObjectAfterCommit(photo.getObjectKey());
        if (wasReady) {
            events.publishEvent(new PortfolioPhotoRemoved(photo.getId(), orgId));
        }
    }

    private void deleteObjectAfterCommit(String key) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    blobs.delete(storage.photoBucket(), key);
                } catch (RuntimeException e) {
                    // The row says DELETED, so nothing links to it; the object is an orphan to clean up by prefix.
                    log.error("Could not delete {} from storage after its photo was deleted", key, e);
                }
            }
        });
    }

    private static void requireKept(Photo photo) {
        if (!photo.isVisibleToOwner()) {
            if (photo.getStatus() == Photo.Status.DELETED) {
                throw notFound();
            }
            throw new ApiException(HttpStatus.CONFLICT, StylistError.WRONG_STATE, "Finish uploading this photo first");
        }
    }

    private PhotoView view(Photo p) {
        URI url = p.isVisibleToOwner() ? blobs.presignDownload(storage.photoBucket(), p.getObjectKey()) : null;
        return new PhotoView(p.getId(), p.getStatus(), url, p.getContentType(), p.getWidth(), p.getHeight(), p.getService(),
                p.getHairType(), p.getTexture(), p.getLength(), p.isBefore(), p.getPairPhotoId(), p.getPosition(), p.getCreatedAt());
    }

    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Photo not found");
    }
}
