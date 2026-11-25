package com.lookmate.app.stylists;

import java.net.URI;
import java.net.URISyntaxException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** The rules for what a stylist types into their profile: the booking link, the Instagram handle and the page address. */
final class ProfileFieldRules {

    static final int MAX_URL_LENGTH = 500;
    private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9._]{1,30}");
    /** As the database's check (V15): 3 to 40, letters, digits and hyphens, a letter or digit at each end. */
    private static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9-]{1,38}[a-z0-9]");
    /** Room left for a "-99" when two names give the same address. */
    static final int SLUG_BASE_MAX = 34;

    private ProfileFieldRules() {
    }

    /** Thrown with words a stylist can act on. */
    static final class Invalid extends RuntimeException {
        Invalid(String message) {
            super(message);
        }
    }

    /**
     * The booking link, trimmed, or null if blank. It must be an https link with a host and no user info: it is
     * the one place we send clients, so nothing that could be a phishing link in disguise.
     */
    static String bookingUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String url = raw.strip();
        if (url.length() > MAX_URL_LENGTH) {
            throw new Invalid("The booking link is too long");
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new Invalid("The booking link isn't a web address. Copy it from your booking app's share button.");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()
                || uri.getRawUserInfo() != null || !uri.getHost().contains(".")) {
            throw new Invalid("The booking link must start with https:// and be your booking page's address");
        }
        return url;
    }

    /** Which booking app a link goes to, from its host. Null for no link. */
    static BookingPlatform platform(String bookingUrl) {
        if (bookingUrl == null) {
            return null;
        }
        String host = URI.create(bookingUrl).getHost().toLowerCase(Locale.ROOT);
        if (onDomain(host, "booksy.com")) {
            return BookingPlatform.BOOKSY;
        }
        if (onDomain(host, "vagaro.com")) {
            return BookingPlatform.VAGARO;
        }
        if (onDomain(host, "styleseat.com")) {
            return BookingPlatform.STYLESEAT;
        }
        if (onDomain(host, "glossgenius.com")) {
            return BookingPlatform.GLOSSGENIUS;
        }
        if (onDomain(host, "square.site") || onDomain(host, "squareup.com")) {
            return BookingPlatform.SQUARE;
        }
        return BookingPlatform.OTHER;
    }

    private static boolean onDomain(String host, String domain) {
        return host.equals(domain) || host.endsWith("." + domain);
    }

    /**
     * The Instagram handle without the @, or null if blank. Accepts "@name", "name" or a profile link
     * ("instagram.com/name"), since stylists paste whichever they have.
     */
    static String instagram(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String handle = raw.strip();
        int at = handle.toLowerCase(Locale.ROOT).indexOf("instagram.com/");
        if (at >= 0) {
            handle = handle.substring(at + "instagram.com/".length());
            int end = handle.indexOf('/');
            if (end >= 0) {
                handle = handle.substring(0, end);
            }
            int query = handle.indexOf('?');
            if (query >= 0) {
                handle = handle.substring(0, query);
            }
        }
        if (handle.startsWith("@")) {
            handle = handle.substring(1);
        }
        if (!HANDLE.matcher(handle).matches()) {
            throw new Invalid("That isn't an Instagram handle: letters, numbers, dots and underscores, up to 30");
        }
        return handle;
    }

    /**
     * The page address a stylist typed (combpatible.app/s/<slug>), lower-cased, or null if blank. Accepts the whole
     * link too, since stylists paste whichever they have.
     */
    static String slug(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String slug = raw.strip().toLowerCase(Locale.ROOT);
        int at = slug.indexOf("/s/");
        if (at >= 0) {
            slug = slug.substring(at + 3);
        }
        if (slug.endsWith("/")) {
            slug = slug.substring(0, slug.length() - 1);
        }
        if (!SLUG.matcher(slug).matches()) {
            throw new Invalid("Your page address can use letters, numbers and hyphens, 3 to 40 of them, starting and "
                    + "ending with a letter or number");
        }
        return slug;
    }

    /** A page address from a display name: "Ana María López" gives "ana-maria-lopez". Always valid, before de-duplicating. */
    static String slugFrom(String displayName) {
        String folded = Normalizer.normalize(displayName == null ? "" : displayName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-");
        String base = trimHyphens(folded);
        if (base.length() > SLUG_BASE_MAX) {
            base = trimHyphens(base.substring(0, SLUG_BASE_MAX));
        }
        if (base.isEmpty()) {
            return "stylist";
        }
        return base.length() < 3 ? base + "-stylist" : base;
    }

    private static String trimHyphens(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '-') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '-') {
            end--;
        }
        return s.substring(start, end);
    }
}
