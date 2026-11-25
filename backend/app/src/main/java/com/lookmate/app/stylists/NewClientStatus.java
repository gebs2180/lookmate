package com.lookmate.app.stylists;

/**
 * Whether a stylist is taking new clients (idea 3, docs/research/feature-ideas.md). Shown to clients in plain words and
 * nothing else: Zach, 2026-11-25, "Not taking clients should not prevent them from matching". No module may filter or
 * rank on it.
 */
public enum NewClientStatus {
    /** The default. */
    TAKING,
    /** "Taking new clients, usually about N weeks out." */
    TAKING_WITH_WAIT,
    /** "Not taking new clients right now." Still matched, still in the directory, still bookable. */
    NOT_TAKING
}
