package com.lookmate.app.intake;

import com.lookmate.app.api.ApiException;
import com.lookmate.app.notify.ClientEmails;
import com.lookmate.app.storage.BlobStore;
import com.lookmate.app.storage.PhotoDecodes;
import com.lookmate.app.storage.StorageProperties;
import com.lookmate.app.storage.UploadCheck;
import com.lookmate.app.stylists.HairLength;
import com.lookmate.app.stylists.HairType;
import com.lookmate.app.stylists.ServiceType;
import com.lookmate.app.stylists.Texture;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** The client's side of intake: the quiz, the inspiration upload, and the client's own view through their link. */
@Service
class IntakeService {

    enum Code { EMAIL_CHANGE_LIMIT, TOO_MANY_FOR_ADDRESS, NOT_UPLOADED, IMAGE_REJECTED, PHOTO_ALREADY_CONFIRMED, PHOTO_DELETED, UNSUPPORTED_PHOTO,
        BUSY }

    /**
     * Where the client is, in their words: the page shows one of these. {@code FAILED}: we couldn't read their photo for
     * matching (decided 2026-11-22: a retake prompt and a link to every stylist); while {@code retakeAllowed}, a new photo
     * through the usual upload calls starts the request again.
     */
    enum Stage { PHOTO_NEEDED, RECEIVED, MATCHING, READY, CLOSED, FAILED }

    record NewRequest(String email, HairType hairType, Texture texture, HairLength length, Integer budgetTier,
                      String neighborhood, ServiceType service, List<Quiz.PastIssue> pastIssues, String pastIssuesNote, String contentType,
                      long sizeBytes) {
    }

    record Created(String token, String statusPath, BlobStore.PresignedUpload upload) {
    }

    /**
     * The client's own request. {@code firstName} is only set on requests from before the quiz stopped asking for it.
     * {@code asks}: what the client asked of their next appointment (the quiet / honest-advice chips), so the page can
     * suggest a booking note; the other past-issue answers aren't echoed. {@code retakeAllowed}: the stage is
     * {@code FAILED} and the client may add a different photo ({@code POST .../inspiration}, upload, then
     * {@code .../inspiration/confirm}); false once retention deleted the photo, when only the directory is left.
     */
    record ClientView(Stage stage, String firstName, String service, String hairType, String texture, String length, Instant createdAt,
                      boolean photoUploaded, boolean photoDeleted, boolean retakeAllowed, Instant photoDeletesAt, int photoKeptDays, String photoUrl,
                      String emailHint, boolean emailChangeAllowed, List<Quiz.PastIssue> asks, List<ClientMatch> matches) {
    }

    /** What the quiz tells clients before they send anything: how long their photo is kept, how large it may be. */
    record Settings(int photoKeptDays, long maxPhotoBytes) {
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
    private final PhotoDecodes decodes;
    private final UploadCheck uploads;
    private final TransactionTemplate transaction;
    private final String defaultMarket;
    private final int photoDays;

    IntakeService(MatchRequestRows rows, BlobStore blobs, StorageProperties storage, IntakeProperties properties, ClientEmails emails,
                  ObjectProvider<ClientMatchList> matchList, PhotoDecodes decodes, UploadCheck uploads, PlatformTransactionManager transactions,
                  InspirationRetention retention, @Value("${lookmate.default-market}") String defaultMarket) {
        this.rows = rows;
        this.blobs = blobs;
        this.storage = storage;
        this.properties = properties;
        this.emails = emails;
        this.matchList = matchList;
        this.decodes = decodes;
        this.uploads = uploads;
        this.transaction = new TransactionTemplate(transactions);
        this.defaultMarket = defaultMarket;
        // The date the page promises comes from the job that keeps it (review 2026-11-22, finding 40).
        this.photoDays = retention.days();
    }

    Settings settings() {
        return new Settings(photoDays, storage.maxUploadBytes());
    }

    /**
     * The quiz. The email is optional (CLAUDE.md, 2026-11-20): the quiz doesn't ask it, and the client may add it on their
     * results page ({@link #setEmail}). One given here (older clients of the API) is treated the same way: capped per
     * address, and the link is emailed. Abuse is limited per IP ({@link PublicRateLimit}). No first name is taken any
     * more (review 2026-11-22, finding 4: it put any 40 characters of the sender's into an email to any address).
     */
    @Transactional
    Created create(NewRequest q) {
        requirePhotoFile(q.contentType(), q.sizeBytes());
        String email = blankToNull(q.email());
        Instant now = Instant.now();
        if (email != null) {
            rows.lockEmail(email);
            requireAddressUnderCap(email, now);
            rows.logEmailUse(addressHash(email), now);
        }
        UUID requestId = UUID.randomUUID();
        String token = LinkTokens.newToken();
        String key = objectKey(requestId);
        List<String> issues = q.pastIssues() == null ? List.of() : q.pastIssues().stream().distinct().map(Enum::name).toList();
        rows.insert(new MatchRequestRows.NewRequest(UUID.randomUUID(), requestId, defaultMarket, email,
                q.hairType().name(), name(q.texture()), name(q.length()), q.budgetTier(), blankToNull(q.neighborhood()), issues,
                blankToNull(q.pastIssuesNote()), q.service().name(), key, q.contentType(), q.sizeBytes(), LinkTokens.hash(token),
                now.plus(properties.linkTtl()), now));
        if (email != null) {
            emails.listLink(requestId, email, null,LinkTokens.path(token), ClientEmails.ListState.WAITING, 1, now);
        }
        log.info("Match request {} created{}", requestId, email == null ? "" : " with an email");
        return new Created(token, LinkTokens.path(token), presign(key, q.contentType(), q.sizeBytes()));
    }

    /**
     * A fresh upload slot, for when the first one expired or the upload failed. On a FAILED request (matching couldn't
     * read the photo) it's the retake: the old photo is deleted and the request waits for a new one, as when it was made;
     * confirming that sends the request to matching again.
     */
    @Transactional
    BlobStore.PresignedUpload uploadSlot(String token, String contentType, long sizeBytes) {
        requirePhotoFile(contentType, sizeBytes);
        MatchRequestRows.Row row = rows.findLocked(requestId(token)).orElseThrow(IntakeService::notFound);
        Instant now = Instant.now();
        if (retakeAllowed(row)) {
            // Uploads are If-None-Match: *, so the old photo has to go before the new one can take its place.
            blobs.delete(storage.photoBucket(), row.objectKey());
            rows.resetForRetake(row.id(), contentType, sizeBytes, now);
            log.info("Match request {}: the client is retaking a photo matching couldn't use ({})", row.id(), row.statusReason());
        } else {
            requireAwaitingPhoto(row);
            rows.setUploadDeclared(row.id(), contentType, sizeBytes, now);
        }
        return presign(row.objectKey(), contentType, sizeBytes);
    }

    /**
     * Checks the upload and keeps it only without its location and device metadata. A photo that isn't a JPEG or PNG
     * within the limits is deleted, so the client can upload another to the same slot. Confirming twice is harmless.
     *
     * <p>Reading and stripping a photo of up to the upload limit takes several times its size in memory, and this is a
     * public endpoint: the check waits for one of the instance's decode turns ({@link PhotoDecodes}) outside any
     * transaction, so a burst holds no connections or row locks while it waits, and answers 503 {@code BUSY} when no turn
     * comes up within {@code lookmate.intake.confirm-wait} (review 2026-11-22, finding 1).
     */
    ClientView confirm(String token) {
        UUID id = requestId(token);
        MatchRequestRows.Row current = rows.find(id).orElseThrow(IntakeService::notFound);
        if (current.sha256() != null || current.inspirationDeletedAt() != null) {
            return transaction.execute(s -> view(current));
        }
        if (blobs.head(storage.photoBucket(), current.objectKey()).isEmpty()) {
            throw notUploaded();
        }
        Checked checked;
        try {
            checked = decodes.withTurn(properties.confirmWait(), () -> transaction.execute(s -> check(id)));
        } catch (PhotoDecodes.Busy e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, Code.BUSY, "We're checking a lot of photos right now. Try again in a minute.");
        }
        if (checked.refusal() != null) {
            throw checked.refusal();
        }
        return checked.view();
    }

    /** The check's outcome. A refusal is returned, not thrown, so the photo's deletion and the row stay committed. */
    private record Checked(ClientView view, ApiException refusal) {
    }

    private Checked check(UUID id) {
        MatchRequestRows.Row row = rows.findLocked(id).orElseThrow(IntakeService::notFound);
        if (row.sha256() != null || row.inspirationDeletedAt() != null) {
            return new Checked(view(row), null);
        }
        UploadCheck.Result result = uploads.check(row.objectKey(), null, null).orElseThrow(IntakeService::notUploaded);
        if (!result.accepted()) {
            return new Checked(null, reject(row, result.refusal().name()));
        }
        rows.setInspirationReady(row.id(), result.contentType(), result.size(), result.sha256(), Instant.now());
        log.info("Match request {}: inspiration photo confirmed ({}x{} {})", row.id(), result.width(), result.height(), result.format());
        return new Checked(view(rows.find(row.id()).orElseThrow()), null);
    }

    private static ApiException notUploaded() {
        return new ApiException(HttpStatus.CONFLICT, Code.NOT_UPLOADED, "We haven't received your photo yet. Try uploading it again.");
    }

    /**
     * The client gives (or corrects) their email on their request page, after the reveal, and is sent the link to the
     * page they're on. Setting it again replaces it and sends the link again, up to
     * {@code lookmate.intake.max-email-changes-per-request} times (then 409 {@code EMAIL_CHANGE_LIMIT}). One address may
     * be set on {@code lookmate.intake.max-link-emails-per-address-per-day} requests a day (then 429
     * {@code TOO_MANY_FOR_ADDRESS}), so this can't flood someone's inbox: every time an address is set counts, even after
     * it's replaced. A new address cancels the request's emails that haven't gone yet, so a mistyped one doesn't get the
     * private link. The "matches ready" email and the "picks added" one go only to clients who gave an email.
     */
    @Transactional
    ClientView setEmail(String token, String email) {
        UUID id = requestId(token);
        String address = email.strip();
        Instant now = Instant.now();
        rows.lockEmail(address);
        MatchRequestRows.Row row = rows.findLocked(id).orElseThrow(IntakeService::notFound);
        if (row.emailChanges() >= properties.maxEmailChangesPerRequest()) {
            throw new ApiException(HttpStatus.CONFLICT, Code.EMAIL_CHANGE_LIMIT,
                    "You've changed the email for this request a few times already. Bookmark this page to keep your link.");
        }
        requireAddressUnderCap(address, now);
        rows.logEmailUse(addressHash(address), now);
        rows.setEmail(row.clientId(), address, now);
        ClientView view = view(rows.find(id).orElseThrow());
        ClientEmails.ListState state = view.stage() != Stage.READY ? ClientEmails.ListState.WAITING
                : view.matches().isEmpty() ? ClientEmails.ListState.NO_CLOSE_MATCH : ClientEmails.ListState.READY;
        int cancelled = emails.cancelPendingForAddressChange(id, now);
        if (cancelled > 0) {
            log.info("Match request {}: {} unsent email(s) to the previous address cancelled", id, cancelled);
        }
        emails.listLink(id, address, null, LinkTokens.path(token), state, row.emailChanges() + 1, now);
        log.info("Match request {}: the client gave an email ({})", id, state);
        return view;
    }

    private void requireAddressUnderCap(String email, Instant now) {
        if (rows.emailUsesSince(addressHash(email), now.minus(Duration.ofDays(1))) >= properties.maxLinkEmailsPerAddressPerDay()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, Code.TOO_MANY_FOR_ADDRESS,
                    "We've sent that address a few links today already. Bookmark this page instead, or try again tomorrow.");
        }
    }

    /** What the per-address cap counts by, so the log doesn't keep the address a second time. */
    static String addressHash(String email) {
        return LinkTokens.sha256(email.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    @Transactional(readOnly = true)
    ClientView view(String token) {
        return view(rows.find(requestId(token)).orElseThrow(IntakeService::notFound));
    }

    private ClientView view(MatchRequestRows.Row row) {
        MatchRequests.Status status = MatchRequests.Status.valueOf(row.status());
        Stage stage = switch (status) {
            case NEW -> row.sha256() == null && row.inspirationDeletedAt() == null ? Stage.PHOTO_NEEDED : Stage.RECEIVED;
            case MATCHING, IN_REVIEW -> Stage.MATCHING;
            case FAILED -> Stage.FAILED;
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
                deleted, retakeAllowed(row), deleted ? null : row.createdAt().plus(photoDays, ChronoUnit.DAYS), photoDays, photoUrl, emailHint(row.email()),
                row.emailChanges() < properties.maxEmailChangesPerRequest(), asks(row.pastIssues()), matches);
    }

    /** The chips that ask something of the next appointment, in the enum's order; unknown codes are skipped. */
    static List<Quiz.PastIssue> asks(List<String> pastIssues) {
        return Arrays.stream(Quiz.PastIssue.values()).filter(Quiz.PastIssue::isAsk).filter(p -> pastIssues.contains(p.name())).toList();
    }

    /**
     * The address the client gave, partly hidden ({@code a•••@example.com}): enough to recognise it on their page, not to
     * read it off a shared screen. Null when they haven't given one.
     */
    static String emailHint(String email) {
        if (email == null) {
            return null;
        }
        int at = email.lastIndexOf('@');
        if (at <= 0) {
            return "•••";
        }
        return email.substring(0, 1) + "•••" + email.substring(at);
    }

    /** The request a token opens. Every failure is the same 404, so a guess learns nothing. */
    private UUID requestId(String token) {
        if (!LinkTokens.wellFormed(token)) {
            throw notFound();
        }
        return rows.requestIdForToken(LinkTokens.hash(token), Instant.now()).orElseThrow(IntakeService::notFound);
    }

    /** A FAILED request whose photo retention hasn't deleted: the client may replace it. */
    private static boolean retakeAllowed(MatchRequestRows.Row row) {
        return MatchRequests.Status.FAILED.name().equals(row.status()) && row.inspirationDeletedAt() == null;
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
