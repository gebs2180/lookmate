package com.lookmate.app.stylists;

import com.lookmate.app.api.ApiException;
import com.lookmate.app.identity.Consents;
import com.lookmate.app.identity.CurrentUser;
import com.lookmate.app.identity.Directory;
import com.lookmate.app.identity.OrgAccess;
import com.lookmate.app.identity.OrgType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** A stylist's own profile: what they see on their home page, saving it, publishing and hiding it. */
@Service
@EnableConfigurationProperties(StylistsProperties.class)
class ProfileService {

    /** What still stands between the profile and LIVE. */
    enum Missing { TERMS, PROFILE, LOCATION, BOOKING_URL, TAGGED_PHOTOS }

    record ProfileView(String displayName, UUID locationId, String market, String bio, List<ServiceType> specialties,
                       String bookingUrl, BookingPlatform bookingPlatform, String instagram, Integer priceTier,
                       ProfileStatus status, Instant publishedAt, Instant updatedAt, boolean demo, Practice practice) {
    }

    /**
     * @param profile         null until the first save
     * @param minTaggedPhotos tagged READY photos needed to publish
     * @param missing         empty when the profile can be published
     */
    record StylistHome(UUID orgId, String orgName, boolean termsAccepted, ProfileView profile, int readyPhotos,
                       int taggedPhotos, int minTaggedPhotos, int maxPhotos, List<Missing> missing) {
    }

    /** {@code practice}: the whole "What to expect in my chair" section; null clears it (all of it is optional). */
    record ProfileRequest(String displayName, UUID locationId, String bio, List<ServiceType> specialties,
                          String bookingUrl, String instagram, Integer priceTier, PracticeRequest practice) {
    }

    /** As {@link Practice}, every field optional. */
    record PracticeRequest(List<PracticeLabel> labels, List<Language> languages, List<PriceRequest> fromPrices,
                           Consultation consultation, Boolean priceDependsOnHair) {
    }

    record PriceRequest(ServiceType service, Integer fromDollars) {
    }

    static final int MAX_FROM_DOLLARS = 10_000;

    private final StylistProfileRepository profiles;
    private final SalonLocationRepository locations;
    private final PhotoRepository photos;
    private final OrgAccess access;
    private final Consents consents;
    private final CurrentUser currentUser;
    private final Directory directory;
    private final StylistsProperties properties;
    private final com.lookmate.app.storage.StorageProperties storage;
    private final ApplicationEventPublisher events;
    private final String defaultMarket;

    ProfileService(StylistProfileRepository profiles, SalonLocationRepository locations, PhotoRepository photos,
                   OrgAccess access, Consents consents, CurrentUser currentUser, Directory directory,
                   StylistsProperties properties, com.lookmate.app.storage.StorageProperties storage,
                   ApplicationEventPublisher events, @Value("${lookmate.default-market:columbus-oh}") String defaultMarket) {
        this.profiles = profiles;
        this.locations = locations;
        this.photos = photos;
        this.access = access;
        this.consents = consents;
        this.currentUser = currentUser;
        this.directory = directory;
        this.properties = properties;
        this.storage = storage;
        this.events = events;
        this.defaultMarket = defaultMarket;
    }

    @Transactional(readOnly = true)
    StylistHome home(UUID orgId) {
        access.require(orgId, OrgType.STYLIST);
        StylistProfile profile = profiles.findById(orgId).orElse(null);
        boolean terms = termsAccepted(orgId);
        int ready = (int) photos.countByOrgIdAndStatusIn(orgId, List.of(Photo.Status.READY));
        int tagged = (int) photos.countTaggedReady(orgId);
        return new StylistHome(orgId, directory.orgName(orgId), terms, profile == null ? null : view(profile), ready, tagged,
                properties.minTaggedPhotosToPublish(), storage.maxPhotosPerStylist(), missing(profile, terms, tagged));
    }

    /** Creates or updates the profile. A LIVE profile must stay publishable: it can't lose its link or location. */
    @Transactional
    ProfileView save(UUID orgId, ProfileRequest request) {
        access.require(orgId, OrgType.STYLIST);
        consents.requireAccepted(orgId);
        StylistProfile.ProfileFields fields = fields(request);
        StylistProfile profile = profiles.findLocked(orgId).orElseGet(() -> new StylistProfile(orgId));
        profile.update(fields);
        if (profile.getStatus() == ProfileStatus.LIVE) {
            List<Missing> missing = missing(profile, true, (int) photos.countTaggedReady(orgId));
            if (!missing.isEmpty()) {
                throw notReady("A live profile needs " + describe(missing) + ". Hide it first to remove them.");
            }
        }
        return view(profiles.save(profile));
    }

    @Transactional
    ProfileView publish(UUID orgId) {
        access.require(orgId, OrgType.STYLIST);
        consents.requireAccepted(orgId);
        StylistProfile profile = profiles.findLocked(orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, StylistError.NO_PROFILE, "Save your profile first"));
        List<Missing> missing = missing(profile, true, (int) photos.countTaggedReady(orgId));
        if (!missing.isEmpty()) {
            throw notReady("Before your profile can go live, add " + describe(missing) + ".");
        }
        if (profile.getStatus() != ProfileStatus.LIVE) {
            profile.publish();
            events.publishEvent(new StylistPublished(orgId));
        }
        return view(profile);
    }

    /** Takes the profile out of matching and browsing. No terms check: anyone in the org may always hide it. */
    @Transactional
    ProfileView hide(UUID orgId) {
        access.require(orgId, OrgType.STYLIST);
        StylistProfile profile = profiles.findLocked(orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, StylistError.NO_PROFILE, "Save your profile first"));
        if (profile.getStatus() != ProfileStatus.HIDDEN) {
            profile.hide();
            events.publishEvent(new StylistHidden(orgId));
        }
        return view(profile);
    }

    /** Admin: marks a stylist as made up, for the proof of concept. Their profile must exist. */
    @Transactional
    ProfileView markDemo(UUID orgId) {
        access.require(orgId, OrgType.STYLIST);
        StylistProfile profile = profiles.findLocked(orgId)
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, StylistError.NO_PROFILE, "Save the profile first"));
        profile.markDemo();
        return view(profile);
    }

    /** Admin: hides every LIVE demo stylist, e.g. when the real ones join. Returns how many were hidden. */
    @Transactional
    int hideDemos() {
        List<StylistProfile> live = profiles.findByDemoTrueAndStatus(ProfileStatus.LIVE);
        for (StylistProfile p : live) {
            p.hide();
            events.publishEvent(new StylistHidden(p.getOrgId()));
        }
        return live.size();
    }

    private boolean termsAccepted(UUID orgId) {
        return currentUser.isAdmin() || consents.acceptedAt(orgId).isPresent();
    }

    private List<Missing> missing(StylistProfile profile, boolean terms, int tagged) {
        List<Missing> missing = new ArrayList<>();
        if (!terms) {
            missing.add(Missing.TERMS);
        }
        if (profile == null) {
            missing.add(Missing.PROFILE);
        } else {
            if (profile.getLocationId() == null) {
                missing.add(Missing.LOCATION);
            }
            if (profile.getBookingUrl() == null) {
                missing.add(Missing.BOOKING_URL);
            }
        }
        if (tagged < properties.minTaggedPhotosToPublish()) {
            missing.add(Missing.TAGGED_PHOTOS);
        }
        return missing;
    }

    private String describe(List<Missing> missing) {
        return String.join(", ", missing.stream().map(m -> switch (m) {
            case TERMS -> "your acceptance of the terms";
            case PROFILE -> "a profile";
            case LOCATION -> "your Salon Lofts location";
            case BOOKING_URL -> "your booking link";
            case TAGGED_PHOTOS -> "at least " + properties.minTaggedPhotosToPublish() + " photos tagged with a service and hair type";
        }).toList());
    }

    private StylistProfile.ProfileFields fields(ProfileRequest r) {
        String name = r.displayName() == null ? "" : r.displayName().strip();
        if (name.isEmpty() || name.length() > 100) {
            throw invalid("Enter the name clients know you by (up to 100 characters)");
        }
        String bio = LocationController.blankToNull(r.bio());
        if (bio != null && bio.length() > 1000) {
            throw invalid("Keep the bio under 1000 characters");
        }
        if (r.priceTier() != null && (r.priceTier() < 1 || r.priceTier() > 4)) {
            throw invalid("Price tier is 1 to 4");
        }
        String market = defaultMarket;
        if (r.locationId() != null) {
            SalonLocation location = locations.findById(r.locationId()).filter(SalonLocation::isActive)
                    .orElseThrow(() -> invalid("Pick your location from the list"));
            market = location.getMarket();
        }
        List<ServiceType> specialties = r.specialties() == null ? List.of()
                : r.specialties().stream().filter(java.util.Objects::nonNull).distinct().toList();
        try {
            String bookingUrl = ProfileFieldRules.bookingUrl(r.bookingUrl());
            return new StylistProfile.ProfileFields(name, r.locationId(), market, bio, specialties,
                    bookingUrl, ProfileFieldRules.platform(bookingUrl), ProfileFieldRules.instagram(r.instagram()), r.priceTier(),
                    practice(r.practice(), specialties));
        } catch (ProfileFieldRules.Invalid e) {
            throw invalid(e.getMessage());
        }
    }

    /**
     * The practice section, normalised: no nulls or repeats, and from-prices only for services the stylist offers (a
     * price left over from an unticked specialty is dropped, not an error).
     */
    private static Practice practice(PracticeRequest p, List<ServiceType> specialties) {
        if (p == null) {
            return Practice.NONE;
        }
        List<PracticeLabel> labels = p.labels() == null ? List.of()
                : p.labels().stream().filter(java.util.Objects::nonNull).distinct().toList();
        List<Language> languages = p.languages() == null ? List.of()
                : p.languages().stream().filter(java.util.Objects::nonNull).distinct().toList();
        java.util.Map<ServiceType, Practice.FromPrice> prices = new java.util.LinkedHashMap<>();
        for (PriceRequest price : p.fromPrices() == null ? List.<PriceRequest>of() : p.fromPrices()) {
            if (price == null || price.service() == null || price.fromDollars() == null || !specialties.contains(price.service())) {
                continue;
            }
            if (price.fromDollars() < 1 || price.fromDollars() > MAX_FROM_DOLLARS) {
                throw invalid("Enter starting prices in whole dollars, from $1 to $10,000");
            }
            if (prices.put(price.service(), new Practice.FromPrice(price.service(), price.fromDollars())) != null) {
                throw invalid("Give one starting price per service");
            }
        }
        return new Practice(labels, languages, List.copyOf(prices.values()), p.consultation(),
                Boolean.TRUE.equals(p.priceDependsOnHair()));
    }

    private static ApiException invalid(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, StylistError.INVALID_FIELD, message);
    }

    private static ApiException notReady(String message) {
        return new ApiException(HttpStatus.CONFLICT, StylistError.NOT_READY_TO_PUBLISH, message);
    }

    static ProfileView view(StylistProfile p) {
        return new ProfileView(p.getDisplayName(), p.getLocationId(), p.getMarket(), p.getBio(), p.getSpecialties(),
                p.getBookingUrl(), p.getBookingPlatform(), p.getInstagram(), p.getPriceTier(), p.getStatus(),
                p.getPublishedAt(), p.getUpdatedAt(), p.isDemo(), p.getPractice());
    }
}
