package com.lookmate.app.matching;

/** Hair type as the client and the stylist's photo tags give it, in order from straight to coily. */
public enum HairType {
    STRAIGHT, WAVY, CURLY, COILY;

    /** Null for null or a value this version doesn't know. */
    static HairType parse(String value) {
        if (value == null) {
            return null;
        }
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    String label() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
