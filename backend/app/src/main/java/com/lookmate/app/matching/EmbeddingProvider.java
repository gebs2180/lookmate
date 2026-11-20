package com.lookmate.app.matching;

import java.util.List;

/**
 * Turns an image into a vector (docs/embedding-providers.md). Which provider runs is
 * {@code lookmate.matching.provider}; only {@code fake} exists until the team chooses one. Implementations must
 * not keep or train on the images they are sent (CLAUDE.md, Privacy).
 */
public interface EmbeddingProvider {

    /**
     * Provider and model, stored with every vector (e.g. {@code replicate:siglip-so400m}). Vectors from different
     * models are never compared: a new model re-embeds everything under its own name.
     */
    String model();

    /** One image's embedding. Throws if the image can't be embedded; the worker backs off and retries. */
    float[] embed(byte[] image, String contentType);

    /** Several at once, in order. Providers with a batch API override this. */
    default List<float[]> embedBatch(List<Image> images) {
        return images.stream().map(i -> embed(i.bytes(), i.contentType())).toList();
    }

    record Image(byte[] bytes, String contentType) {
    }
}
