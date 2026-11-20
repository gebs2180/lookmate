package com.lookmate.app.matching;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Matching v1's ranking, as a pure function (docs/architecture.md, "Matching v1"). Ranks stylists, not photos: a
 * stylist's score mixes the mean of their best {@code bestK} photo similarities (missing photos count as zero, so one
 * lucky photo doesn't win) with how many similar photos they have. Each photo's similarity is weighted by how
 * compatible its hair type is with the client's ({@link HairCompatibility}).
 *
 * <pre>score = (1 - countWeight) * mean(best k weighted similarities) + countWeight * min(1, similar / countCap)</pre>
 *
 * where {@code similar} sums the hair-type weights of photos at or above {@code similarThreshold}.
 */
final class Scoring {

    private Scoring() {
    }

    /** One portfolio photo and its cosine similarity to the inspiration photo (1 = same direction). */
    record PhotoMatch(UUID photoId, UUID orgId, HairType hairType, double similarity) {
    }

    /**
     * @param bestK            photos averaged for the similarity part
     * @param similarThreshold cosine similarity at which a photo counts as "similar" (model-dependent)
     * @param countWeight      0 to 1: the share of the score that comes from the count of similar photos
     * @param countCap         similar photos at which the count part is full
     * @param evidence         photos kept as evidence per stylist
     * @param keep             stylists kept
     */
    record Settings(int bestK, double similarThreshold, double countWeight, int countCap, int evidence, int keep, HairCompatibility hair) {

        Settings {
            if (bestK < 1 || countCap < 1 || evidence < 1 || keep < 1) {
                throw new IllegalArgumentException("bestK, countCap, evidence and keep must be at least 1");
            }
            if (countWeight < 0 || countWeight > 1) {
                throw new IllegalArgumentException("countWeight must be between 0 and 1");
            }
        }
    }

    /**
     * A ranked stylist. {@code similarByType} counts the similar photos per hair type, the client's own type first;
     * {@code photosByType} counts every compatible photo considered.
     */
    record Ranked(UUID orgId, double score, List<UUID> evidence, Map<HairType, Integer> similarByType,
                  Map<HairType, Integer> photosByType) {

        int similar() {
            return similarByType.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    static List<Ranked> rank(HairType requested, List<PhotoMatch> photos, Settings s) {
        Map<UUID, List<Weighted>> byOrg = new LinkedHashMap<>();
        for (PhotoMatch p : photos) {
            double w = s.hair().weight(requested, p.hairType());
            if (w <= 0) {
                continue;
            }
            double sim = Double.isNaN(p.similarity()) ? 0 : Math.max(0, Math.min(1, p.similarity()));
            byOrg.computeIfAbsent(p.orgId(), k -> new ArrayList<>()).add(new Weighted(p, w, sim * w));
        }
        List<Ranked> ranked = new ArrayList<>();
        byOrg.forEach((org, list) -> ranked.add(score(org, list, requested, s)));
        ranked.sort(Comparator.comparingDouble(Ranked::score).reversed()
                .thenComparing(Comparator.comparingInt(Ranked::similar).reversed())
                .thenComparing(Ranked::orgId));
        return ranked.size() > s.keep() ? List.copyOf(ranked.subList(0, s.keep())) : List.copyOf(ranked);
    }

    private record Weighted(PhotoMatch photo, double weight, double value) {
    }

    private static Ranked score(UUID org, List<Weighted> list, HairType requested, Settings s) {
        list.sort(Comparator.comparingDouble(Weighted::value).reversed().thenComparing(w -> w.photo().photoId()));
        double best = 0;
        for (int i = 0; i < Math.min(s.bestK(), list.size()); i++) {
            best += list.get(i).value();
        }
        best /= s.bestK();

        double similarWeight = 0;
        Map<HairType, Integer> similarByType = byType(requested);
        Map<HairType, Integer> photosByType = byType(requested);
        for (Weighted w : list) {
            HairType t = w.photo().hairType();
            if (t != null) {
                photosByType.merge(t, 1, Integer::sum);
            }
            if (w.photo().similarity() >= s.similarThreshold()) {
                similarWeight += w.weight();
                if (t != null) {
                    similarByType.merge(t, 1, Integer::sum);
                }
            }
        }
        similarByType.values().removeIf(n -> n == 0);
        photosByType.values().removeIf(n -> n == 0);
        double count = Math.min(1, similarWeight / s.countCap());
        double score = (1 - s.countWeight()) * best + s.countWeight() * count;
        List<UUID> evidence = list.stream().limit(s.evidence()).map(w -> w.photo().photoId()).toList();
        return new Ranked(org, score, evidence, similarByType, photosByType);
    }

    /** An ordered map with the client's own type first, then the rest from straight to coily. */
    private static Map<HairType, Integer> byType(HairType requested) {
        Map<HairType, Integer> m = new LinkedHashMap<>();
        if (requested != null) {
            m.put(requested, 0);
        }
        for (HairType t : HairType.values()) {
            m.putIfAbsent(t, 0);
        }
        return m;
    }
}
