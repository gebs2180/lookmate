package com.lookmate.app.matching;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The one-line "why" shown with a match, e.g. "12 similar cuts on wavy hair". */
final class Reasons {

    private Reasons() {
    }

    private record Noun(String one, String many) {
        String of(int n) {
            return n == 1 ? one : many;
        }
    }

    private static final Map<String, Noun> SERVICE = Map.ofEntries(
            Map.entry("CUT", new Noun("cut", "cuts")),
            Map.entry("COLOR", new Noun("color", "colors")),
            Map.entry("BALAYAGE", new Noun("balayage", "balayages")),
            Map.entry("HIGHLIGHTS", new Noun("highlight job", "highlight jobs")),
            Map.entry("EXTENSIONS", new Noun("extension install", "extension installs")),
            Map.entry("BRAIDS", new Noun("braid style", "braid styles")),
            Map.entry("LOCS", new Noun("loc style", "loc styles")),
            Map.entry("SILK_PRESS", new Noun("silk press", "silk presses")),
            Map.entry("PERM", new Noun("perm", "perms")),
            Map.entry("TREATMENT", new Noun("treatment", "treatments")),
            Map.entry("STYLING", new Noun("style", "styles")),
            Map.entry("OTHER", new Noun("look", "looks")));

    private static final Noun LOOK = new Noun("look", "looks");

    static String forRanked(Scoring.Ranked r, String service, HairType requested) {
        Noun noun = service == null ? LOOK : SERVICE.getOrDefault(service, LOOK);
        if (r.similar() > 0) {
            return counts(r.similarByType(), "similar ", noun, requested);
        }
        return counts(r.photosByType(), "", noun, requested) + ", none very close to this look";
    }

    /** A stylist the reviewer added by hand. */
    static final String ADDED_BY_REVIEWER = "Added by a reviewer";

    /** "12 similar cuts on wavy hair and 3 on curly hair", at most two hair types, the client's own first. */
    private static String counts(Map<HairType, Integer> byType, String adjective, Noun noun, HairType requested) {
        List<String> parts = new ArrayList<>();
        boolean first = true;
        int total = 0;
        for (var e : byType.entrySet()) {
            total += e.getValue();
            if (parts.size() < 2) {
                parts.add(first ? "%d %s%s on %s hair".formatted(e.getValue(), adjective, noun.of(e.getValue()), e.getKey().label())
                        : "%d on %s hair".formatted(e.getValue(), e.getKey().label()));
                first = false;
            }
        }
        if (requested == null || parts.isEmpty()) {
            return "%d %s%s".formatted(total, adjective, noun.of(total));
        }
        return String.join(" and ", parts);
    }
}
