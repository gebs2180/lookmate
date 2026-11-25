package com.lookmate.app.stylists;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The generic "Before your first visit" notes, from {@code stylists/first-visit-notes.json} (docs/research/
 * first-visit-notes.md says how to update it). Loaded once at startup; an unknown key or an empty note fails the start,
 * so a typo can't silently drop advice.
 *
 * <p>Pick order, most specific first: {@code SERVICE+HAIRTYPE}, then the service, then the hair type, then the
 * {@code any.*} notes in file order; at most {@link #MAX} in all. The stylist's own notes ({@link FirstVisit}) come before
 * all of these and are the caller's to show.
 */
@Component
class FirstVisitNotes {

    static final int MAX = 4;
    static final String RESOURCE = "stylists/first-visit-notes.json";

    private final Map<String, String> notes;
    private final List<String> anyKeys;

    FirstVisitNotes() {
        this(load(RESOURCE));
    }

    FirstVisitNotes(Map<String, String> notes) {
        notes.forEach(FirstVisitNotes::check);
        this.notes = Map.copyOf(notes);
        this.anyKeys = notes.keySet().stream().filter(k -> k.startsWith("any.")).toList();
    }

    /** The generic notes for a service and hair type; either may be null, which skips the notes keyed by it. */
    List<FirstVisitNote> pick(ServiceType service, HairType hairType) {
        List<String> keys = new ArrayList<>();
        if (service != null && hairType != null) {
            keys.add(service.name() + "+" + hairType.name());
        }
        if (service != null) {
            keys.add(service.name());
        }
        if (hairType != null) {
            keys.add(hairType.name());
        }
        keys.addAll(anyKeys);
        return keys.stream().filter(notes::containsKey).limit(MAX).map(k -> new FirstVisitNote(k, notes.get(k))).toList();
    }

    private static void check(String key, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("First-visit note " + key + " is empty: leave the key out instead");
        }
        if (key.startsWith("any.") && key.length() > 4) {
            return;
        }
        String[] parts = key.split("[+]", -1);
        try {
            if (parts.length == 1) {
                if (!isService(parts[0])) {
                    HairType.valueOf(parts[0]);
                }
                return;
            }
            if (parts.length == 2) {
                ServiceType.valueOf(parts[0]);
                HairType.valueOf(parts[1]);
                return;
            }
        } catch (IllegalArgumentException e) {
            // Falls through to the error below.
        }
        throw new IllegalStateException("Unknown first-visit note key " + key
                + ": use any.<name>, a ServiceType, a HairType or SERVICE+HAIRTYPE");
    }

    private static boolean isService(String code) {
        for (ServiceType s : ServiceType.values()) {
            if (s.name().equals(code)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> load(String resource) {
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            Map<String, Object> raw = JsonMapper.builder().build().readValue(in, LinkedHashMap.class);
            Map<String, String> out = new LinkedHashMap<>();
            raw.forEach((k, v) -> {
                if (!k.startsWith("_")) {
                    out.put(k, v instanceof String s ? s : null);
                }
            });
            return out;
        } catch (IOException e) {
            throw new IllegalStateException("Can't read " + resource, e);
        }
    }
}
