package com.lookmate.app.matching;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Random;

/**
 * A deterministic stand-in for a real model: the vector is seeded by the image's SHA-256, so the same bytes always
 * give the same unit vector (cosine similarity 1) and different bytes give nearly unrelated ones (close to 0). For
 * tests and local development; it says nothing about how alike two looks are.
 */
class FakeEmbeddingProvider implements EmbeddingProvider {

    private final int dimension;

    FakeEmbeddingProvider(int dimension) {
        if (dimension < 2) {
            throw new IllegalArgumentException("dimension must be at least 2");
        }
        this.dimension = dimension;
    }

    @Override
    public String model() {
        return "fake:sha256-" + dimension;
    }

    @Override
    public float[] embed(byte[] image, String contentType) {
        if (image == null || image.length == 0) {
            throw new IllegalArgumentException("empty image");
        }
        Random random = new Random(ByteBuffer.wrap(sha256(image)).getLong());
        float[] v = new float[dimension];
        double norm = 0;
        for (int i = 0; i < dimension; i++) {
            v[i] = (float) random.nextGaussian();
            norm += v[i] * v[i];
        }
        norm = Math.sqrt(norm);
        for (int i = 0; i < dimension; i++) {
            v[i] = (float) (v[i] / norm);
        }
        return v;
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
