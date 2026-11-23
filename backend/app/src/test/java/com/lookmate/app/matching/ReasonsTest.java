package com.lookmate.app.matching;

import com.lookmate.app.stylists.HairType;
import static com.lookmate.app.stylists.HairType.CURLY;
import static com.lookmate.app.stylists.HairType.WAVY;
import static org.assertj.core.api.Assertions.assertThat;

import com.lookmate.app.matching.Scoring.PhotoMatch;
import com.lookmate.app.stylists.ServiceType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReasonsTest {

    private static final Scoring.Settings SETTINGS = new Scoring.Settings(3, 0.8, 0.25, 10, 4, 5, new HairCompatibility(0.5));
    private static final UUID ORG = new UUID(0, 1);

    /** Review #38: a service added to {@link ServiceType} gets a word in the reason, not the "look" fallback. */
    @Test
    void everyServiceHasANoun() {
        assertThat(Reasons.SERVICE.keySet()).containsExactlyInAnyOrder(ServiceType.values());
    }

    @Test
    void countsSimilarWorkOnTheClientsHairType() {
        assertThat(reason(WAVY, "CUT", photos(12, WAVY, 0.9))).isEqualTo("12 similar cuts on wavy hair");
        assertThat(reason(WAVY, "CUT", photos(1, WAVY, 0.9))).isEqualTo("1 similar cut on wavy hair");
    }

    @Test
    void mentionsTheNeighbouringTypeSecond() {
        List<PhotoMatch> p = new ArrayList<>(photos(3, CURLY, 0.9));
        p.addAll(photos(5, WAVY, 0.9));
        assertThat(reason(WAVY, "BALAYAGE", p)).isEqualTo("5 similar balayages on wavy hair and 3 on curly hair");
        assertThat(reason(WAVY, "SILK_PRESS", photos(2, CURLY, 0.95))).isEqualTo("2 similar silk presses on curly hair");
    }

    @Test
    void saysSoWhenNothingIsClose() {
        assertThat(reason(WAVY, "COLOR", photos(4, WAVY, 0.3))).isEqualTo("4 colors on wavy hair, none very close to this look");
    }

    @Test
    void withoutAServiceOrHairTypeItCountsLooks() {
        assertThat(reason(null, null, photos(2, WAVY, 0.9))).isEqualTo("2 similar looks");
    }

    private static String reason(HairType requested, String service, List<PhotoMatch> photos) {
        return Reasons.forRanked(Scoring.rank(requested, photos, SETTINGS).getFirst(), service, requested);
    }

    private static int next;

    private static List<PhotoMatch> photos(int n, HairType type, double sim) {
        List<PhotoMatch> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new PhotoMatch(new UUID(7, ++next), ORG, type, sim));
        }
        return out;
    }
}
