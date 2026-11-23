package com.lookmate.app.matching;

import com.lookmate.app.stylists.HairType;
import java.util.Locale;

/**
 * Reading the stylists module's {@link HairType} from the strings intake and the photo tags store (the V2 check
 * constraint values). Matching has no hair-type enum of its own, so a value added in one place can't drift from the
 * other (review 2026-11-22, #38). The order of {@link HairType} (straight to coily) is what {@link HairCompatibility}
 * measures distance by.
 */
final class HairTypes {

    private HairTypes() {
    }

    /** Null for null or a value this version doesn't know. */
    static HairType parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return HairType.valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** "wavy", for the reason line. */
    static String label(HairType type) {
        return type.name().toLowerCase(Locale.ROOT);
    }
}
