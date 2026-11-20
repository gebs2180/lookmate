package com.lookmate.app.matching;

import java.util.Arrays;
import java.util.List;

/**
 * How much a portfolio photo on one hair type counts for a client with another. The same type counts fully; a
 * neighbouring type (straight-wavy, wavy-curly, curly-coily) counts at {@code adjacentWeight}; anything further apart
 * not at all, so a coily client is never matched on straight-hair work alone. A client who gave no hair type is
 * matched on every type at full weight.
 *
 * @param adjacentWeight 0 to 1; 0 turns neighbouring types off
 */
record HairCompatibility(double adjacentWeight) {

    HairCompatibility {
        if (adjacentWeight < 0 || adjacentWeight > 1) {
            throw new IllegalArgumentException("adjacentWeight must be between 0 and 1");
        }
    }

    double weight(HairType requested, HairType photo) {
        if (requested == null) {
            return 1;
        }
        if (photo == null) {
            return 0;
        }
        int apart = Math.abs(requested.ordinal() - photo.ordinal());
        return apart == 0 ? 1 : apart == 1 ? adjacentWeight : 0;
    }

    /** The photo hair types worth fetching for a request: those with a weight above zero. */
    List<HairType> compatible(HairType requested) {
        return Arrays.stream(HairType.values()).filter(t -> weight(requested, t) > 0).toList();
    }
}
