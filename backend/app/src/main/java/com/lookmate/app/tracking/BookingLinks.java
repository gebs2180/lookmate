package com.lookmate.app.tracking;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The stylist's booking link with Combpatible's UTM tags added. Only absolute https links with a host and no user info
 * pass; anything else is refused, so the redirect can never be pointed somewhere unexpected (no open redirect).
 */
final class BookingLinks {

    static final String SOURCE = "combpatible";
    static final String MEDIUM = "referral";
    /** {@code utm_content} of a Book click from the stylist directory, which has no match. */
    static final String DIRECTORY = "directory";

    private BookingLinks() {
    }

    /**
     * The link to send the client to, or empty if {@code bookingUrl} isn't a safe https link. The stylist's own query
     * parameters are kept in order; any {@code utm_source}, {@code utm_medium}, {@code utm_campaign} or
     * {@code utm_content} already there is replaced by ours, so the platform sees one value for each.
     */
    static Optional<URI> tagged(String bookingUrl, String market, UUID matchId) {
        return tagged(bookingUrl, market, matchId.toString());
    }

    /** As above, with {@code utm_content} set to {@code content} ({@link #DIRECTORY} for a stylist picked from the directory). */
    static Optional<URI> tagged(String bookingUrl, String market, String content) {
        if (bookingUrl == null || bookingUrl.isBlank() || bookingUrl.length() > 2048) {
            return Optional.empty();
        }
        URI uri;
        try {
            uri = new URI(bookingUrl.strip());
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
        if (!uri.isAbsolute() || uri.isOpaque() || !"https".equals(uri.getScheme().toLowerCase(Locale.ROOT))
                || uri.getHost() == null || uri.getHost().isBlank() || uri.getRawUserInfo() != null) {
            return Optional.empty();
        }

        Map<String, String> ours = new LinkedHashMap<>();
        ours.put("utm_source", SOURCE);
        ours.put("utm_medium", MEDIUM);
        ours.put("utm_campaign", market);
        ours.put("utm_content", content);

        List<String> params = new ArrayList<>();
        if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
            for (String pair : uri.getRawQuery().split("&")) {
                if (pair.isEmpty()) {
                    continue;
                }
                String rawName = pair.split("=", 2)[0];
                String name;
                try {
                    name = URLDecoder.decode(rawName, StandardCharsets.UTF_8);
                } catch (IllegalArgumentException e) {
                    name = rawName;
                }
                if (!ours.containsKey(name.toLowerCase(Locale.ROOT))) {
                    params.add(pair);
                }
            }
        }
        ours.forEach((name, value) -> params.add(name + "=" + URLEncoder.encode(value, StandardCharsets.UTF_8)));

        StringBuilder out = new StringBuilder("https://").append(uri.getRawAuthority());
        out.append(uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
        out.append('?').append(String.join("&", params));
        if (uri.getRawFragment() != null) {
            out.append('#').append(uri.getRawFragment());
        }
        try {
            return Optional.of(new URI(out.toString()));
        } catch (URISyntaxException e) {
            return Optional.empty();
        }
    }
}
