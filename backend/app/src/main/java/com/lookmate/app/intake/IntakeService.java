package com.lookmate.app.intake;

import com.lookmate.app.api.ApiException;
import com.lookmate.app.notify.ClientEmails;
import com.lookmate.app.storage.BlobStore;
import com.lookmate.app.storage.ImageGuard;
import com.lookmate.app.storage.StorageProperties;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The client's side of intake: the quiz, the inspiration upload, and the client's own view through their link. */
@Service
class IntakeService {

    enum Code { TOO_MANY_OPEN_REQUESTS, NOT_UPLOADED, IMAGE_REJECTED, PHOTO_ALREADY_CONFIRMED, PHOTO_DELETED, UNSUPPORTED_PHOTO }

    /** Where the client is, in their words: the page shows one of these. */
    enum Stage { PHOTO_NEEDED, RECEIVED, MATCHING, READY, CLOSED }

    record NewRequest(String email, String firstName, Quiz.HairType hairType, Quiz.Texture texture, Quiz.Length length, Integer budgetTier,
                      String neighborhood, Quiz.Service service, List<Quiz.PastIssue> pastIssues, String pastIssuesNote, String contentType,
                      long sizeBytes) {
    }

    record Created(String token, String statusPath, BlobStore.PresignedUpload upload) {
    }

    record ClientView(Stage stage, String firstName, String service, String hairType, String texture, String length, Instant createdAt,
                      boolean photoUploaded, boolean photoDeleted, Instant photoDeletesAt, String photoUrl, List<ClientMatch> matches) {
    }

    static final Set<String> PHOTO_TYPES = Set.of("image/jpeg", "image/png");
    static final String NOT_FOUND = "This link doesn't work. It may have expired: start a new request, or use the link in your latest email.";

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);

    private final MatchRequestRows rows;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final IntakeProperties properties;
    private final ClientEmails emails;
    private final ObjectProvider<ClientMatchList> matchList;
    private final String defaultMarket;
    private final int photoDays;

    IntakeService(MatchRequestRows rows, BlobStore blobs, StorageProperties storage, IntakeProperties properties, ClientEmails emails,
                  ObjectProvider<ClientMatchList> matchList, @Value("${lookmate.default-market}") String defaultMarket,
                  @Value("${lookmate.retention.inspiration-photo-days:30}") int photoDays) {
        this.rows = rows;
        this.blobs = blobs;
        this.storage = storage;
        this.properties = properties;
        this.emails = emails;
        this.matchList = matchList;
        this.defaultMarket = defaultMarket;
        this.photoDays = photoDays;
    }

    @Transactional
    Created create(NewRequest q) {
        requirePhotoFile(q.contentType(), q.sizeBytes());
        String email = q.email().strip();
        Instant now = Instant.now();
        rows.lockEmail(email);
        if (rows.openRequestsSince(email, now.minus(Duration.ofDays(1))) >= properties.maxOpenRequestsPerEmailPerDay()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, Code.TOO_MANY_OPEN_REQUESTS,
                    "You already have requests in progress. Check your email for their links, or try again tomorrow.");
        }
        UUID requestId = UUID.randomUUID();
        String token = LinkTokens.newToken();
        String key = objectKey(requestId);
        List<String> issues = q.pastIssues() == null ? List.of() : q.pastIssues().stream().distinct().map(Enum::name).toList();
        rows.insert(new MatchRequestRows.NewRequest(UUID.randomUUID(), requestId, defaultMarket, email, blankToNull(q.firstName()),
                q.hairType().name(), name(q.texture()), name(q.length()), q.budgetTier(), blankToNull(q.neighborhood()), issues,
                blankToNull(q.pastIssuesNote()), q.service().name(), key, q.contentType(), q.sizeBytes(), LinkTokens.hash(token),
                now.plus(properties.linkTtl()), now));
        emails.requestReceived(requestId, email, blankToNull(q.firstName()), LinkTokens.path(token), photoDays, now);
        log.info("Match request {} created", requestId);
        return new Created(token, LinkTokens.path(token), presign(key, q.contentType(), q.sizeBytes()));
    }

    /** A fresh upload slot, for when the first one expired or the upload failed. */
    @Transactional
    BlobStore.PresignedUpload uploadSlot(String token, String contentType, long sizeBytes) {
        requirePhotoFile(contentType, sizeBytes);
        MatchRequestRows.Row row = rows.findLocked(requestId(token)).orElseThrow(IntakeService::notFound);
        requireAwaitingPhoto(row);
        rows.setUploadDeclared(row.id(), contentType, sizeBytes, Instant.now());
        return presign(row.objectKey(), contentType, sizeBytes);
    }

    /**
     * Checks the upload and keeps it only without its location and device metadata. A photo that isn't a JPEG or PNG
     * within the limits is deleted, so the client can upload another to the same slot. Confirming twice is harmless.
     */
    @Transactional
    ClientView confirm(String token) {
        MatchRequestRows.Row row = rows.findLocked(requestId(token)).orElseThrow(IntakeService::notFound);
        if (row.sha256() != null || row.inspirationDeletedAt() != null) {
            return view(row);
        }
        String bucket = storage.photoBucket();
        BlobStore.StoredObject stored = blobs.head(bucket, row.objectKey())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, Code.NOT_UPLOADED, "We haven't received your photo yet. Try uploading it again."));
        if (stored.size() > storage.maxUploadBytes()) {
            throw reject(row, "too large");
        }
        byte[] bytes = blobs.read(bucket, row.objectKey());
        Optional<ImageGuard.Header> header = ImageGuard.safeHeader(bytes);
        if (header.isEmpty()) {
            throw reject(row, "not a JPEG or PNG within the size limits");
        }
        byte[] clean = PhotoMetadata.withoutMetadata(bytes);
        if (clean == null || ImageGuard.safeHeader(clean).isEmpty()) {
            // Never keep a photo whose metadata we couldn't take out.
            throw reject(row, "metadata could not be removed");
        }
        String contentType = "image/" + header.get().format();
        if (!Arrays.equals(clean, bytes)) {
            blobs.replace(bucket, row.objectKey(), clean, contentType);
        }
        Instant now = Instant.now();
        rows.setInspirationReady(row.id(), contentType, clean.length, LinkTokens.sha256(clean), now);
        log.info("Match request {}: inspiration photo confirmed ({}x{} {})", row.id(), header.get().width(), header.get().height(),
                header.get().format());
        return view(rows.find(row.id()).orElseThrow());
    }

    @Transactional(readOnly = true)
    ClientView view(String token) {
        return view(rows.find(requestId(token)).orElseThrow(IntakeService::notFound));
    }

    private ClientView view(MatchRequestRows.Row row) {
        MatchRequests.Status status = MatchRequests.Status.valueOf(row.status());
        Stage stage = switch (status) {
            case NEW -> row.sha256() == null && row.inspirationDeletedAt() == null ? Stage.PHOTO_NEEDED : Stage.RECEIVED;
            case MATCHING, IN_REVIEW, FAILED -> Stage.MATCHING;
            case PUBLISHED -> Stage.READY;
            case CLOSED -> Stage.CLOSED;
        };
        boolean uploaded = row.sha256() != null;
        boolean deleted = row.inspirationDeletedAt() != null;
        String photoUrl = row.inspirationReady() ? blobs.presignDownload(storage.photoBucket(), row.objectKey()).toString() : null;
        List<ClientMatch> matches = List.of();
        if (status == MatchRequests.Status.PUBLISHED) {
            ClientMatchList provider = matchList.getIfAvailable();
            matches = provider == null ? List.of() : List.copyOf(provider.forClient(row.id()));
        }
        return new ClientView(stage, row.firstName(), row.service(), row.hairType(), row.texture(), row.length(), row.createdAt(), uploaded,
                deleted, deleted ? null : row.createdAt().plus(photoDays, ChronoUnit.DAYS), photoUrl, matches);
    }

    /** The request a token opens. Every failure is the same 404, so a guess learns nothing. */
    private UUID requestId(String token) {
        if (!LinkTokens.wellFormed(token)) {
            throw notFound();
        }
        return rows.requestIdForToken(LinkTokens.hash(token), Instant.now()).orElseThrow(IntakeService::notFound);
    }

    private void requireAwaitingPhoto(MatchRequestRows.Row row) {
        if (row.inspirationDeletedAt() != null) {
            throw new ApiException(HttpStatus.CONFLICT, Code.PHOTO_DELETED, "Your photo was deleted after " + photoDays + " days, as promised.");
        }
        if (row.sha256() != null) {
            throw new ApiException(HttpStatus.CONFLICT, Code.PHOTO_ALREADY_CONFIRMED, "We already have your photo.");
        }
    }

    private void requirePhotoFile(String contentType, long sizeBytes) {
        if (contentType == null || !PHOTO_TYPES.contains(contentType.toLowerCase(Locale.ROOT))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, Code.UNSUPPORTED_PHOTO, "Choose a JPEG or PNG photo.");
        }
        if (sizeBytes <= 0 || sizeBytes > storage.maxUploadBytes()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, Code.UNSUPPORTED_PHOTO,
                    "That photo is too large. Choose one under " + storage.maxUploadBytes() / (1024 * 1024) + " MB.");
        }
    }

    private ApiException reject(MatchRequestRows.Row row, String why) {
        blobs.delete(storage.photoBucket(), row.objectKey());
        log.info("Match request {}: inspiration upload refused ({})", row.id(), why);
        return new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, Code.IMAGE_REJECTED,
                "We couldn't use that file. Choose a JPEG or PNG photo and try again.");
    }

    private BlobStore.PresignedUpload presign(String key, String contentType, long sizeBytes) {
        return blobs.presignUpload(storage.photoBucket(), key, contentType.toLowerCase(Locale.ROOT), sizeBytes);
    }

    static String objectKey(UUID requestId) {
        return "intake/" + requestId + "/inspiration";
    }

    static ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, NotFound.LINK_NOT_FOUND, NOT_FOUND);
    }

    enum NotFound { LINK_NOT_FOUND }

    private static String name(Enum<?> e) {
        return e == null ? null : e.name();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
