package com.lookmate.app.stylists;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Salon locations. The admin adds and edits them (Salon Lofts' Columbus buildings first, entered through the admin
 * page, not a migration); any signed-in user can list a market's active ones for the profile form.
 */
@RestController
class LocationController {

    /** @param active ignored on create (a new location is active) */
    record LocationRequest(
            @NotBlank @Size(max = 60) String brand,
            @NotBlank @Size(max = 120) String name,
            @NotBlank @Pattern(regexp = "[a-z0-9]+(-[a-z0-9]+)*", message = "lowercase letters, digits and dashes, like columbus-oh") @Size(max = 40) String market,
            @Size(max = 200) String address,
            @Size(max = 80) String city,
            @Size(max = 2) String state,
            @Size(max = 10) String zip,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            Boolean active) {
    }

    record AdminLocationView(UUID id, String brand, String name, String market, String address, String city,
                             String state, String zip, Double latitude, Double longitude, boolean active,
                             Instant createdAt) {
    }

    private final SalonLocationRepository locations;
    private final Stylists stylists;
    private final String defaultMarket;

    LocationController(SalonLocationRepository locations, Stylists stylists,
                       @Value("${lookmate.default-market:columbus-oh}") String defaultMarket) {
        this.locations = locations;
        this.stylists = stylists;
        this.defaultMarket = defaultMarket;
    }

    /** Active locations, for the stylist's profile form. */
    @GetMapping("/api/locations")
    List<Stylists.Location> active(@RequestParam(required = false) String market) {
        return stylists.locations(market == null || market.isBlank() ? defaultMarket : market);
    }

    @GetMapping("/api/admin/locations")
    @Transactional(readOnly = true)
    List<AdminLocationView> all(@RequestParam(required = false) String market) {
        var found = market == null || market.isBlank()
                ? locations.findAllByOrderByMarketAscNameAsc()
                : locations.findByMarketOrderByNameAsc(market);
        return found.stream().map(LocationController::view).toList();
    }

    @PostMapping("/api/admin/locations")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    AdminLocationView create(@Valid @RequestBody LocationRequest request) {
        return view(locations.save(new SalonLocation(fields(request, true))));
    }

    @PutMapping("/api/admin/locations/{id}")
    @Transactional
    AdminLocationView update(@PathVariable UUID id, @Valid @RequestBody LocationRequest request) {
        SalonLocation location = locations.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Location not found"));
        location.update(fields(request, request.active() == null ? location.isActive() : request.active()));
        return view(location);
    }

    private static SalonLocation.LocationFields fields(LocationRequest r, boolean active) {
        return new SalonLocation.LocationFields(r.brand().strip(), r.name().strip(), r.market().strip(), blankToNull(r.address()),
                blankToNull(r.city()), r.state() == null || r.state().isBlank() ? null : r.state().strip().toUpperCase(),
                blankToNull(r.zip()), r.latitude(), r.longitude(), active);
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static AdminLocationView view(SalonLocation l) {
        return new AdminLocationView(l.getId(), l.getBrand(), l.getName(), l.getMarket(), l.getAddress(), l.getCity(),
                l.getState(), l.getZip(), l.getLatitude(), l.getLongitude(), l.isActive(), l.getCreatedAt());
    }
}
