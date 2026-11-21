package com.lookmate.app.stylists;

/**
 * What a stylist says about their chair, ticked on their profile and shown to clients on it (2026-11-20). Practices,
 * never identity, politics or religion. Optional; never a filter or a ranking input.
 */
public enum PracticeLabel {
    /** Priced by the service and the hair, not the client's gender. */
    GENDER_NEUTRAL_PRICING,
    /** Uses clients' names and pronouns and styles for how they want to look. */
    LGBTQ_AFFIRMING,
    /** On request: music down or off, unscented products, scissors instead of clippers. */
    SENSORY_FRIENDLY,
    /** Kids' cuts welcome. Only ever the positive: there is no "adults only". */
    KIDS_WELCOME
}
