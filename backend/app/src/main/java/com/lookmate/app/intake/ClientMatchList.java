package com.lookmate.app.intake;

import java.util.List;
import java.util.UUID;

/**
 * The extension point for the client's match list (docs/modules/intake.md). The matching module provides the bean;
 * without one, a published request shows no matches. Only called for PUBLISHED requests, by the client's own link.
 */
public interface ClientMatchList {

    /** The client-visible matches (final rank set, not hidden), best first. */
    List<ClientMatch> forClient(UUID matchRequestId);
}
