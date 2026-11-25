package com.lookmate.app.stylists;

import com.lookmate.app.persistence.AssignedIdEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private boolean demo;
    private String[] practiceLabels;
    private String[] languages;
    private String consultation;
    private boolean priceDependsOnHair;
    /** Service code to whole dollars (V8). */
    @ElementCollection
    @CollectionTable(name = "stylist_price", joinColumns = @JoinColumn(name = "org_id"))
    @MapKeyColumn(name = "service")
    @Column(name = "from_dollars")
    private Map<String, Integer> fromPrices = new HashMap<>();
    /** The stylist's own first-visit facts and note (V13). */
    private boolean colourConsultationFirst;
    private boolean depositForNewClients;
    private String firstVisitNote;
    /** New-client status (V13): shown to clients only, never used in matching. */
    private String newClients;
    private Short newClientsWeeks;
    private Instant newClientsConfirmedAt;
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
        this.practiceLabels = new String[0];
        this.languages = new String[0];
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        this.newClients = NewClientStatus.TAKING.name();
        this.newClientsConfirmedAt = createdAt;
    }

    /** The fields the stylist edits, already normalised and checked. */
    record ProfileFields(String displayName, UUID locationId, String market, String bio, List<ServiceType> specialties,
                         String bookingUrl, BookingPlatform bookingPlatform, String instagram, Integer priceTier,
                         Practice practice) {
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
        Practice practice = f.practice();
        this.practiceLabels = practice.labels().stream().distinct().map(Enum::name).toArray(String[]::new);
        this.languages = practice.languages().stream().distinct().map(Enum::name).toArray(String[]::new);
        this.consultation = practice.consultation() == null ? null : practice.consultation().name();
        this.priceDependsOnHair = practice.priceDependsOnHair();
        this.fromPrices.clear();
        practice.fromPrices().forEach(p -> fromPrices.put(p.service().name(), p.fromDollars()));
        FirstVisit firstVisit = practice.firstVisit();
        this.colourConsultationFirst = firstVisit.colourConsultationFirst();
        this.depositForNewClients = firstVisit.depositForNewClients();
        this.firstVisitNote = firstVisit.note();
        this.updatedAt = Instant.now();
    }

    /** Sets the new-client status; setting it counts as confirming it. */
    void setNewClients(NewClients status, Instant now) {
        this.newClients = status.status().name();
        this.newClientsWeeks = status.weeksOut() == null ? null : status.weeksOut().shortValue();
        this.newClientsConfirmedAt = now;
        this.updatedAt = now;
    }

    /** "Still accurate": the status stands as it is, confirmed now. */
    void confirmNewClients(Instant now) {
        this.newClientsConfirmedAt = now;
        this.updatedAt = now;
    }

    void publish() {
        if (status != ProfileStatus.LIVE) {
            status = ProfileStatus.LIVE;
            publishedAt = Instant.now();
            updatedAt = publishedAt;
        }
    }

    /** A made-up stylist for the proof of concept. Only the admin sets this. */
    void markDemo() {
        demo = true;
        updatedAt = Instant.now();
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

    /** Labels, languages, from-prices (in the order of the specialties) and the consultation. */
    Practice getPractice() {
        List<Practice.FromPrice> prices = getSpecialties().stream()
                .filter(s -> fromPrices.containsKey(s.name()))
                .map(s -> new Practice.FromPrice(s, fromPrices.get(s.name())))
                .toList();
        return new Practice(codes(practiceLabels, PracticeLabel.class), codes(languages, Language.class), prices,
                consultation == null ? null : Consultation.valueOf(consultation), priceDependsOnHair,
                new FirstVisit(colourConsultationFirst, depositForNewClients, firstVisitNote), getNewClients());
    }

    NewClients getNewClients() {
        return new NewClients(newClients == null ? NewClientStatus.TAKING : NewClientStatus.valueOf(newClients),
                newClientsWeeks == null ? null : newClientsWeeks.intValue());
    }

    Instant getNewClientsConfirmedAt() {
        return newClientsConfirmedAt;
    }

    private static <E extends Enum<E>> List<E> codes(String[] values, Class<E> type) {
        return values == null ? List.of() : Arrays.stream(values).map(v -> Enum.valueOf(type, v)).toList();
    }

    boolean isDemo() {
        return demo;
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
