package com.lookmate.app.matching;

import static com.lookmate.app.matching.HairType.COILY;
import static com.lookmate.app.matching.HairType.CURLY;
import static com.lookmate.app.matching.HairType.STRAIGHT;
import static com.lookmate.app.matching.HairType.WAVY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.lookmate.app.matching.Scoring.PhotoMatch;
import com.lookmate.app.matching.Scoring.Ranked;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ScoringTest {

    private static final Scoring.Settings SETTINGS = new Scoring.Settings(3, 0.8, 0.25, 10, 4, 5, new HairCompatibility(0.5));

    private static final UUID A = id(1);
    private static final UUID B = id(2);
    private static final UUID C = id(3);

    @Test
    void oneLuckyPhotoDoesNotBeatSeveralGoodOnes() {
        List<PhotoMatch> photos = List.of(
                photo(A, WAVY, 0.99),
                photo(B, WAVY, 0.85), photo(B, WAVY, 0.84), photo(B, WAVY, 0.83));
        List<Ranked> ranked = Scoring.rank(WAVY, photos, SETTINGS);
        assertThat(ranked).extracting(Ranked::orgId).containsExactly(B, A);
    }

    @Test
    void theScoreIsTheBestKMeanPlusTheCountPart() {
        List<PhotoMatch> photos = List.of(photo(A, WAVY, 0.9), photo(A, WAVY, 0.6), photo(A, WAVY, 0.3), photo(A, WAVY, 0.1));
        Ranked r = Scoring.rank(WAVY, photos, SETTINGS).getFirst();
        // best 3: (0.9 + 0.6 + 0.3) / 3 = 0.6; similar (>= 0.8): 1 of a cap of 10
        assertThat(r.score()).isCloseTo(0.75 * 0.6 + 0.25 * 0.1, within(1e-9));
        assertThat(r.similar()).isEqualTo(1);
    }

    @Test
    void moreSimilarPhotosRankHigherWhenTheBestAreEqual() {
        List<PhotoMatch> photos = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            photos.add(photo(A, WAVY, 0.9));
            photos.add(photo(B, WAVY, 0.9));
        }
        for (int i = 0; i < 6; i++) {
            photos.add(photo(B, WAVY, 0.85));
        }
        assertThat(Scoring.rank(WAVY, photos, SETTINGS)).extracting(Ranked::orgId).containsExactly(B, A);
    }

    @Test
    void adjacentHairTypesCountAtReducedWeightAndDistantOnesNotAtAll() {
        List<PhotoMatch> photos = List.of(
                photo(A, CURLY, 0.9), photo(A, CURLY, 0.9), photo(A, CURLY, 0.9), // client's own type
                photo(B, WAVY, 0.9), photo(B, WAVY, 0.9), photo(B, WAVY, 0.9),    // neighbouring
                photo(C, STRAIGHT, 0.99), photo(C, STRAIGHT, 0.99), photo(C, STRAIGHT, 0.99)); // two apart
        List<Ranked> ranked = Scoring.rank(CURLY, photos, SETTINGS);
        assertThat(ranked).extracting(Ranked::orgId).containsExactly(A, B);
        assertThat(ranked.get(1).score()).isCloseTo(ranked.get(0).score() / 2, within(1e-9));
    }

    @Test
    void coilyRequestsAreNeverMatchedOnStraightOrWavyWork() {
        List<PhotoMatch> photos = List.of(photo(A, STRAIGHT, 1.0), photo(B, WAVY, 1.0));
        assertThat(Scoring.rank(COILY, photos, SETTINGS)).isEmpty();
    }

    @Test
    void keepsTheTopFiveWithTheirBestPhotosAsEvidence() {
        List<PhotoMatch> photos = new ArrayList<>();
        for (int org = 1; org <= 8; org++) {
            for (int p = 0; p < 6; p++) {
                photos.add(photo(id(org), WAVY, 0.1 * org - 0.01 * p));
            }
        }
        List<Ranked> ranked = Scoring.rank(WAVY, photos, new Scoring.Settings(3, 0.8, 0.25, 10, 4, 5, new HairCompatibility(0.5)));
        assertThat(ranked).hasSize(5).extracting(Ranked::orgId).containsExactly(id(8), id(7), id(6), id(5), id(4));
        Ranked best = ranked.getFirst();
        assertThat(best.evidence()).hasSize(4);
        List<Double> sims = best.evidence().stream()
                .map(e -> photos.stream().filter(p -> p.photoId().equals(e)).findFirst().orElseThrow().similarity()).toList();
        assertThat(sims).isSortedAccordingTo((x, y) -> Double.compare(y, x));
    }

    @Test
    void tiesAreBrokenTheSameWayEveryTime() {
        List<PhotoMatch> photos = List.of(photo(B, WAVY, 0.5), photo(A, WAVY, 0.5));
        assertThat(Scoring.rank(WAVY, photos, SETTINGS)).extracting(Ranked::orgId).containsExactly(A, B);
    }

    @Test
    void negativeAndOddSimilaritiesAreClamped() {
        List<PhotoMatch> photos = List.of(photo(A, WAVY, -0.4), photo(B, WAVY, Double.NaN), photo(C, WAVY, 1.3));
        List<Ranked> ranked = Scoring.rank(WAVY, photos, SETTINGS);
        assertThat(ranked.getFirst().orgId()).isEqualTo(C);
        assertThat(ranked).allSatisfy(r -> assertThat(r.score()).isBetween(0.0, 1.0));
    }

    @Test
    void countsSimilarPhotosPerHairTypeTheClientsOwnFirst() {
        List<PhotoMatch> photos = List.of(photo(A, CURLY, 0.9), photo(A, WAVY, 0.95), photo(A, WAVY, 0.9), photo(A, WAVY, 0.2));
        Ranked r = Scoring.rank(WAVY, photos, SETTINGS).getFirst();
        assertThat(r.similarByType()).containsExactly(org.assertj.core.api.Assertions.entry(WAVY, 2),
                org.assertj.core.api.Assertions.entry(CURLY, 1));
        assertThat(r.photosByType()).containsEntry(WAVY, 3).containsEntry(CURLY, 1);
    }

    @Test
    void noPhotosNoStylists() {
        assertThat(Scoring.rank(WAVY, List.of(), SETTINGS)).isEmpty();
    }

    private static int next;

    private static PhotoMatch photo(UUID org, HairType type, double similarity) {
        return new PhotoMatch(new UUID(99, ++next), org, type, similarity);
    }

    private static UUID id(int n) {
        return new UUID(0, n);
    }
}
