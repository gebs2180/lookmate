package com.lookmate.app.stylists;

import com.lookmate.app.persistence.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** A stylist org's public profile. One per org, keyed by the org's id. */
@Entity
@Table(name = "stylist_profile")
class StylistProfile extends AssignedIdEntity<UUID> {

    @Id
    private UUID orgId;
    private String displayName;
    private UUID locationId;
    private String market;
    private String bio;
    private String[] specialties;
    private String bookingUrl;
    private String bookingPlatform;
    private String instagram;
    private Short priceTier;
    @Enumerated(EnumType.STRING)
    private ProfileStatus status;
    private Instant publishedAt;
    private Instant createdAt;
    private Instant updatedAt;

    protected StylistProfile() {
    }

    StylistProfile(UUID orgId) {
        this.orgId = orgId;
        this.status = ProfileStatus.DRAFT;
        this.specialties = new String[0];
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    /** The fields the stylist edits, already normalised and checked. */
    record ProfileFields(String displayName, UUID locationId, String market, String bio, List<ServiceType> specialties,
                         String bookingUrl, BookingPlatform bookingPlatform, String instagram, Integer priceTier) {
    }

    void update(ProfileFields f) {
        this.displayName = f.displayName();
        this.locationId = f.locationId();
        this.market = f.market();
        this.bio = f.bio();
        this.specialties = f.specialties().stream().distinct().map(Enum::name).toArray(String[]::new);
        this.bookingUrl = f.bookingUrl();
        this.bookingPlatform = f.bookingPlatform() == null ? null : f.bookingPlatform().name();
        this.instagram = f.instagram();
        this.priceTier = f.priceTier() == null ? null : f.priceTier().shortValue();
        this.updatedAt = Instant.now();
    }

    void publish() {
        if (status != ProfileStatus.LIVE) {
            status = ProfileStatus.LIVE;
            publishedAt = Instant.now();
            updatedAt = publishedAt;
        }
    }

    void hide() {
        status = ProfileStatus.HIDDEN;
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return orgId;
    }

    UUID getOrgId() {
        return orgId;
    }

    String getDisplayName() {
        return displayName;
    }

    UUID getLocationId() {
        return locationId;
    }

    String getMarket() {
        return market;
    }

    String getBio() {
        return bio;
    }

    List<ServiceType> getSpecialties() {
        return specialties == null ? List.of() : Arrays.stream(specialties).map(ServiceType::valueOf).toList();
    }

    String getBookingUrl() {
        return bookingUrl;
    }

    BookingPlatform getBookingPlatform() {
        return bookingPlatform == null ? null : BookingPlatform.valueOf(bookingPlatform);
    }

    String getInstagram() {
        return instagram;
    }

    Integer getPriceTier() {
        return priceTier == null ? null : priceTier.intValue();
    }

    ProfileStatus getStatus() {
        return status;
    }

    Instant getPublishedAt() {
        return publishedAt;
    }

    Instant getUpdatedAt() {
        return updatedAt;
    }
}
