package com.lookmate.app.stylists;

import java.util.UUID;

/** A portfolio photo became READY: an upload was confirmed, or a hidden photo was shown again. Published inside the transaction that made the change. */
public record PortfolioPhotoReady(UUID photoId, UUID orgId) {
}
