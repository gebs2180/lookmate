package com.lookmate.app.stylists;

import com.lookmate.app.persistence.AssignedIdEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Where a stylist works: a Salon Lofts building, say. Added by the admin; deactivated, never deleted. */
@Entity
@Table(name = "salon_location")
class SalonLocation extends AssignedIdEntity<UUID> {

    @Id
    private UUID id;
    private String brand;
    private String name;
    private String market;
    private String address;
    private String city;
    private String state;
    private String zip;
    private Double latitude;
    private Double longitude;
    private boolean active;
    private Instant createdAt;

    protected SalonLocation() {
    }

    SalonLocation(LocationFields fields) {
        this.id = UUID.randomUUID();
        this.createdAt = Instant.now();
        this.active = true;
        update(fields);
    }

    /** The editable fields, already trimmed and checked. */
    record LocationFields(String brand, String name, String market, String address, String city, String state,
                          String zip, Double latitude, Double longitude, boolean active) {
    }

    void update(LocationFields f) {
        this.brand = f.brand();
        this.name = f.name();
        this.market = f.market();
        this.address = f.address();
        this.city = f.city();
        this.state = f.state();
        this.zip = f.zip();
        this.latitude = f.latitude();
        this.longitude = f.longitude();
        this.active = f.active();
    }

    Stylists.Location view() {
        return new Stylists.Location(id, brand, name, market, address, city, state, zip, latitude, longitude);
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getBrand() {
        return brand;
    }

    String getName() {
        return name;
    }

    String getMarket() {
        return market;
    }

    String getAddress() {
        return address;
    }

    String getCity() {
        return city;
    }

    String getState() {
        return state;
    }

    String getZip() {
        return zip;
    }

    Double getLatitude() {
        return latitude;
    }

    Double getLongitude() {
        return longitude;
    }

    boolean isActive() {
        return active;
    }

    Instant getCreatedAt() {
        return createdAt;
    }
}
