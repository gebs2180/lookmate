package com.lookmate.app.stylists;

import java.util.UUID;

/** A portfolio photo's tags changed. Published inside the transaction that made the change. */
public record PortfolioPhotoTagged(UUID photoId, UUID orgId) {
}
