package com.lookmate.app.stylists;

import java.util.UUID;

/** A portfolio photo was hidden or deleted: matching must stop using it. Published inside the transaction that made the change. */
public record PortfolioPhotoRemoved(UUID photoId, UUID orgId) {
}
