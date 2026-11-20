package com.lookmate.app.stylists;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public stylist directory ({@code /api/public/stylists}): no sign-in, rate-limited per IP like intake's public
 * endpoints. Parameters are checked by Spring MVC's own method validation (a bad one is a 400). "Book" isn't here: the booking link never leaves the server except as tracking's redirect
 * ({@code /api/public/book-stylist/{token}/{orgId}}), which needs the client's link token.
 */
@RestController
class DirectoryController {

    private final StylistDirectory directory;
    private final String defaultMarket;

    DirectoryController(StylistDirectory directory, @Value("${lookmate.default-market}") String defaultMarket) {
        this.directory = directory;
        this.defaultMarket = defaultMarket;
    }

    @GetMapping("/api/public/stylists")
    ResponseEntity<StylistDirectory.DirectoryPage> stylists(
            @RequestParam(required = false) @Pattern(regexp = "[a-z0-9-]{1,64}") String market,
            @RequestParam(required = false) ServiceType service,
            @RequestParam(required = false) HairType hairType,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1000) int page) {
        StylistDirectory.DirectoryPage body = directory.page(market == null ? defaultMarket : market, service, hairType, page);
        // The photo links expire: never cached.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
    }
}
