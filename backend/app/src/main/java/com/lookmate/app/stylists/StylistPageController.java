package com.lookmate.app.stylists;

import com.lookmate.app.storage.PhotoDecodes;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;

/**
 * A stylist's public page ({@code combpatible.app/s/<slug>}), the single-stylist view for the directory's portfolio
 * dialog, and what link previews read. No sign-in; rate-limited per IP with the directory (intake's
 * {@code PublicRateLimit}). 404 for anyone not LIVE, and for demo stylists by address: they have no page.
 */
@RestController
class StylistPageController {

    /** How long a preview image may wait for a photo decode turn before a 503. */
    private static final Duration OG_WAIT = Duration.ofSeconds(5);

    private final StylistPages pages;
    private final PhotoDecodes decodes;
    private final String appUrl;

    StylistPageController(StylistPages pages, PhotoDecodes decodes, @Value("${lookmate.notify.email.app-url}") URI appUrl) {
        this.pages = pages;
        this.decodes = decodes;
        String base = appUrl.toString();
        this.appUrl = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }

    /** The page at {@code /s/<slug>}: LIVE, not a demo stylist. */
    @GetMapping("/api/public/stylists/by-slug/{slug}")
    ResponseEntity<StylistPages.StylistPage> bySlug(@PathVariable String slug) {
        // The photo links expire: never cached.
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pages.bySlug(slug).orElseThrow(StylistPageController::notFound));
    }

    /** One LIVE stylist, demo ones included, for the directory's portfolio dialog. */
    @GetMapping("/api/public/stylists/{orgId}")
    ResponseEntity<StylistPages.StylistPage> byOrg(@PathVariable UUID orgId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(pages.byOrg(orgId).orElseThrow(StylistPageController::notFound));
    }

    /**
     * The Open Graph tags for {@code /s/<slug>}, as a tiny HTML page. Caddy sends link-preview bots here (they don't
     * run the web app's JavaScript); people get the web app. Every value is escaped.
     */
    @GetMapping(value = "/api/public/stylists/preview/{slug}", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> preview(@PathVariable String slug) {
        StylistPages.Preview p = pages.preview(slug).orElseThrow(StylistPageController::notFound);
        String url = appUrl + "/s/" + p.slug();
        String title = p.displayName() + " · Combpatible";
        String where = p.locationName() == null ? "" : " at " + p.locationName() + (p.city() == null ? "" : ", " + p.city());
        String description = "Hair stylist" + where + ". See their work on hair like yours, prices and what to expect, then book with them directly.";
        StringBuilder html = new StringBuilder()
                .append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n")
                .append("<title>").append(esc(title)).append("</title>\n")
                .append("<link rel=\"canonical\" href=\"").append(esc(url)).append("\">\n")
                .append("<meta name=\"description\" content=\"").append(esc(description)).append("\">\n")
                .append("<meta property=\"og:type\" content=\"profile\">\n")
                .append("<meta property=\"og:site_name\" content=\"Combpatible\">\n")
                .append("<meta property=\"og:title\" content=\"").append(esc(title)).append("\">\n")
                .append("<meta property=\"og:description\" content=\"").append(esc(description)).append("\">\n")
                .append("<meta property=\"og:url\" content=\"").append(esc(url)).append("\">\n");
        if (p.hasPhotos()) {
            String image = appUrl + "/api/public/stylists/" + p.orgId() + "/og.jpg?v=" + p.updatedAt().getEpochSecond();
            html.append("<meta property=\"og:image\" content=\"").append(esc(image)).append("\">\n")
                    .append("<meta property=\"og:image:type\" content=\"image/jpeg\">\n")
                    .append("<meta property=\"og:image:width\" content=\"").append(StylistPages.OG_WIDTH).append("\">\n")
                    .append("<meta property=\"og:image:height\" content=\"").append(StylistPages.OG_HEIGHT).append("\">\n")
                    .append("<meta property=\"og:image:alt\" content=\"").append(esc("Hair by " + p.displayName())).append("\">\n")
                    .append("<meta name=\"twitter:card\" content=\"summary_large_image\">\n");
        } else {
            html.append("<meta name=\"twitter:card\" content=\"summary\">\n");
        }
        html.append("</head><body><p><a href=\"").append(esc(url)).append("\">").append(esc(title)).append("</a></p></body></html>\n");
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePublic())
                .contentType(new MediaType(MediaType.TEXT_HTML, java.nio.charset.StandardCharsets.UTF_8)).body(html.toString());
    }

    /**
     * The link-preview image: the stylist's own portfolio photos only, never a client photo. The address carries
     * {@code ?v=} from the profile's last change, so it may be cached for hours.
     */
    @GetMapping(value = "/api/public/stylists/{orgId}/og.jpg", produces = MediaType.IMAGE_JPEG_VALUE)
    ResponseEntity<byte[]> ogImage(@PathVariable UUID orgId) {
        byte[] jpeg;
        try {
            jpeg = decodes.withTurn(OG_WAIT, () -> pages.ogImage(orgId)).orElseThrow(StylistPageController::notFound);
        } catch (PhotoDecodes.Busy e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Busy, try again shortly");
        }
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(Duration.ofHours(6)).cachePublic())
                .contentType(MediaType.IMAGE_JPEG).body(jpeg);
    }

    private static String esc(String s) {
        return HtmlUtils.htmlEscape(s, "UTF-8");
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Stylist not found");
    }
}
