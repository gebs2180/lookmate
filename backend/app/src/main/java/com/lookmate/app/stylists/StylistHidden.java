package com.lookmate.app.stylists;

import java.util.UUID;

/** A stylist's profile went HIDDEN. Published inside the transaction that made the change. */
public record StylistHidden(UUID orgId) {
}
