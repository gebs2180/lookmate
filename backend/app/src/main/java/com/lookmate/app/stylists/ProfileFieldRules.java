package com.lookmate.app.stylists;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/** The rules for what a stylist types into their profile: the booking link and the Instagram handle. */
final class ProfileFieldRules {

    static final int MAX_URL_LENGTH = 500;
    private static final Pattern HANDLE = Pattern.compile("[A-Za-z0-9._]{1,30}");

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
}
