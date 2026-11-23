package com.lookmate.app.matching;

import com.lookmate.app.stylists.HairType;
import com.lookmate.app.stylists.ServiceType;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The one-line "why" shown with a match, e.g. "12 similar cuts on wavy hair". */
final class Reasons {

    private Reasons() {
    }

    record Noun(String one, String many) {
        String of(int n) {
            return n == 1 ? one : many;
        }
    }

    /** Keyed by the stylists module's {@link ServiceType}, so a new service can't be missed (ReasonsTest checks). */
    static final Map<ServiceType, Noun> SERVICE = new EnumMap<>(Map.ofEntries(
            Map.entry(ServiceType.CUT, new Noun("cut", "cuts")),
            Map.entry(ServiceType.COLOR, new Noun("color", "colors")),
            Map.entry(ServiceType.BALAYAGE, new Noun("balayage", "balayages")),
            Map.entry(ServiceType.HIGHLIGHTS, new Noun("highlight job", "highlight jobs")),
            Map.entry(ServiceType.EXTENSIONS, new Noun("extension install", "extension installs")),
            Map.entry(ServiceType.BRAIDS, new Noun("braid style", "braid styles")),
            Map.entry(ServiceType.LOCS, new Noun("loc style", "loc styles")),
            Map.entry(ServiceType.SILK_PRESS, new Noun("silk press", "silk presses")),
            Map.entry(ServiceType.PERM, new Noun("perm", "perms")),
            Map.entry(ServiceType.TREATMENT, new Noun("treatment", "treatments")),
            Map.entry(ServiceType.STYLING, new Noun("style", "styles")),
            Map.entry(ServiceType.OTHER, new Noun("look", "looks"))));

    private static final Noun LOOK = new Noun("look", "looks");

    static String forRanked(Scoring.Ranked r, String service, HairType requested) {
        Noun noun = SERVICE.getOrDefault(serviceType(service), LOOK);
        if (r.similar() > 0) {
            return counts(r.similarByType(), "similar ", noun, requested);
        }
        return counts(r.photosByType(), "", noun, requested) + ", none very close to this look";
    }

    /**
     * A stylist the admin added by hand, as the client sees it (Zach, 2026-11-22; review #26). The match keeps source
     * ADMIN, and the client API says {@code pickedByTeam}.
     */
    static final String PICKED_BY_TEAM = "Picked for you by the Combpatible team";

    private static ServiceType serviceType(String service) {
        if (service == null) {
            return null;
        }
        try {
            return ServiceType.valueOf(service);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** "12 similar cuts on wavy hair and 3 on curly hair", at most two hair types, the client's own first. */
    private static String counts(Map<HairType, Integer> byType, String adjective, Noun noun, HairType requested) {
        List<String> parts = new ArrayList<>();
        boolean first = true;
        int total = 0;
        for (var e : byType.entrySet()) {
            total += e.getValue();
            if (parts.size() < 2) {
                parts.add(first ? "%d %s%s on %s hair".formatted(e.getValue(), adjective, noun.of(e.getValue()), HairTypes.label(e.getKey()))
                        : "%d on %s hair".formatted(e.getValue(), HairTypes.label(e.getKey())));
                first = false;
            }
        }
        if (requested == null || parts.isEmpty()) {
            return "%d %s%s".formatted(total, adjective, noun.of(total));
        }
        return String.join(" and ", parts);
    }
}
