package com.lookmate.app.stylists;

import com.lookmate.app.storage.BlobStore;
import com.lookmate.app.storage.StorageProperties;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * What other modules may read about stylists (docs/modules/stylists.md). No access checks: callers decide who
 * may see what. Photos are never public: {@link #photoUrl} is a short-lived signed link.
 */
@Service
public class Stylists {

    /**
     * A LIVE stylist as clients may see them. {@code demo} marks a made-up stylist for the proof of concept: label
     * it ("Sample stylist") wherever clients see it.
     */
    public record LiveProfile(UUID orgId, String displayName, String market, Location location, String bio,
                              List<ServiceType> specialties, String bookingUrl, BookingPlatform bookingPlatform,
                              String instagram, Integer priceTier, boolean demo) {
    }

    /** Any stylist profile with its status: for admin screens. */
    public record ProfileSummary(UUID orgId, String displayName, String market, ProfileStatus status,
                                 UUID locationId, String bookingUrl, int readyPhotos, boolean demo) {
    }

    public record Location(UUID id, String brand, String name, String market, String address, String city,
                           String state, String zip, Double latitude, Double longitude) {
    }

    /** A READY portfolio photo. {@code pairPhotoId} is the other half of a before/after pair, or null. */
    public record PortfolioPhoto(UUID photoId, UUID orgId, ServiceType service, HairType hairType, Texture texture,
                                 HairLength length, boolean before, UUID pairPhotoId, int position,
                                 Integer width, Integer height) {
    }

    /**
     * A stylist's new-client status and when they last set or confirmed it (for the digest's 30-day "still accurate?"
     * link). Never a matching input.
     */
    public record NewClientsStatus(UUID orgId, NewClients newClients, Instant confirmedAt) {
    }

    private final StylistProfileRepository profiles;
    private final SalonLocationRepository locations;
    private final PhotoRepository photos;
    private final BlobStore blobs;
    private final StorageProperties storage;
    private final FirstVisitNotes firstVisitNotes;

    Stylists(StylistProfileRepository profiles, SalonLocationRepository locations, PhotoRepository photos,
             BlobStore blobs, StorageProperties storage, FirstVisitNotes firstVisitNotes) {
        this.profiles = profiles;
        this.locations = locations;
        this.photos = photos;
        this.blobs = blobs;
        this.storage = storage;
        this.firstVisitNotes = firstVisitNotes;
    }

    /**
     * The generic "Before your first visit" notes for a service and hair type, most specific first, at most four.
     * Either may be null. The stylist's own notes ({@link Practice#firstVisit()}) go before these wherever both show.
     */
    public List<FirstVisitNote> firstVisitNotes(ServiceType service, HairType hairType) {
        return firstVisitNotes.pick(service, hairType);
    }

    /** The new-client status of any profile, whatever its status, or empty. */
    @Transactional(readOnly = true)
    public Optional<NewClientsStatus> newClients(UUID orgId) {
        return profiles.findById(orgId).map(p -> new NewClientsStatus(orgId, p.getNewClients(), p.getNewClientsConfirmedAt()));
    }

    /**
     * "Still accurate": confirms the stylist's new-client status as it stands, now. For the digest's confirm link; no
     * access check (the caller has already checked its signed link). Empty when there's no profile.
     */
    @Transactional
    public Optional<NewClientsStatus> confirmNewClients(UUID orgId) {
        return profiles.findLocked(orgId).map(p -> {
            p.confirmNewClients(Instant.now());
            return new NewClientsStatus(orgId, p.getNewClients(), p.getNewClientsConfirmedAt());
        });
    }

    /** The profile if it is LIVE; empty for DRAFT, HIDDEN, missing or a non-stylist org. */
    @Transactional(readOnly = true)
    public Optional<LiveProfile> liveProfile(UUID orgId) {
        return profiles.findById(orgId).filter(p -> p.getStatus() == ProfileStatus.LIVE).map(this::live);
    }

    /** Every LIVE profile in the market, by display name. */
    @Transactional(readOnly = true)
    public List<LiveProfile> liveProfiles(String market) {
        return profiles.findByMarketAndStatusOrderByDisplayNameAsc(market, ProfileStatus.LIVE).stream().map(this::live).toList();
    }

    /** Any profile, whatever its status, or empty. */
    @Transactional(readOnly = true)
    public Optional<ProfileSummary> profile(UUID orgId) {
        return profiles.findById(orgId).map(p -> new ProfileSummary(p.getOrgId(), p.getDisplayName(), p.getMarket(),
                p.getStatus(), p.getLocationId(), p.getBookingUrl(),
                (int) photos.countByOrgIdAndStatusIn(orgId, List.of(Photo.Status.READY)), p.isDemo()));
    }

    /**
     * READY photos of LIVE stylists in the market, tagged with this service and one of these hair types (empty means
     * any), ordered by org then the stylist's own order. Untagged photos never appear.
     */
    @Transactional(readOnly = true)
    public List<PortfolioPhoto> readyPhotos(String market, ServiceType service, Collection<HairType> hairTypes) {
        Collection<HairType> types = hairTypes == null || hairTypes.isEmpty() ? Arrays.asList(HairType.values()) : hairTypes;
        return photos.findMatchable(market, service, types).stream().map(Photo::view).toList();
    }

    /** All READY photos of one stylist, LIVE or not, in their order. Tags may be null. */
    @Transactional(readOnly = true)
    public List<PortfolioPhoto> readyPhotosOf(UUID orgId) {
        return photos.findByOrgIdAndStatusInOrderByPositionAscCreatedAtAsc(orgId, List.of(Photo.Status.READY)).stream()
                .map(Photo::view).toList();
    }

    /** One photo if it is READY, or empty. */
    @Transactional(readOnly = true)
    public Optional<PortfolioPhoto> readyPhoto(UUID photoId) {
        return photos.findByIdAndStatus(photoId, Photo.Status.READY).map(Photo::view);
    }

    /** A short-lived signed GET link for a READY photo, or empty. Ask again for each page view; don't store it. */
    @Transactional(readOnly = true)
    public Optional<URI> photoUrl(UUID photoId) {
        return photos.findByIdAndStatus(photoId, Photo.Status.READY)
                .map(p -> blobs.presignDownload(storage.photoBucket(), p.getObjectKey()));
    }

    /** The stored bytes of a READY photo (metadata already stripped), or empty. Off the request path only. */
    public Optional<byte[]> photoBytes(UUID photoId) {
        // No transaction: reading from storage can take a while and needn't hold a connection.
        return photos.findByIdAndStatus(photoId, Photo.Status.READY)
                .map(p -> blobs.read(storage.photoBucket(), p.getObjectKey()));
    }

    /** Active locations in a market, by name. */
    @Transactional(readOnly = true)
    public List<Location> locations(String market) {
        return locations.findByMarketAndActiveTrueOrderByNameAsc(market).stream().map(SalonLocation::view).toList();
    }

    private LiveProfile live(StylistProfile p) {
        Location location = p.getLocationId() == null ? null : locations.findById(p.getLocationId()).map(SalonLocation::view).orElse(null);
        return new LiveProfile(p.getOrgId(), p.getDisplayName(), p.getMarket(), location, p.getBio(), p.getSpecialties(),
                p.getBookingUrl(), p.getBookingPlatform(), p.getInstagram(), p.getPriceTier(), p.isDemo());
    }
}
