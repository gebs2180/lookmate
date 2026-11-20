package com.lookmate.app.stylists;

import java.util.UUID;

/** A stylist's profile went LIVE. Published inside the transaction that made the change. */
public record StylistPublished(UUID orgId) {
}
