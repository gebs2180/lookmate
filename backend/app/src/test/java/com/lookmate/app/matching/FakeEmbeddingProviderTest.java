package com.lookmate.app.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class FakeEmbeddingProviderTest {

    private final FakeEmbeddingProvider fake = new FakeEmbeddingProvider(64);

    @Test
    void sameBytesSameUnitVector() {
        float[] a = fake.embed(bytes("look-1"), "image/jpeg");
        float[] b = fake.embed(bytes("look-1"), "image/png");
        assertThat(a).hasSize(64).containsExactly(b);
        assertThat(dot(a, a)).isCloseTo(1.0, within(1e-5));
    }

    @Test
    void differentBytesAreFarApart() {
        assertThat(Math.abs(dot(fake.embed(bytes("look-1"), "image/jpeg"), fake.embed(bytes("look-2"), "image/jpeg")))).isLessThan(0.6);
    }

    @Test
    void batchIsInOrderAndTheModelNamesTheDimension() {
        List<float[]> v = fake.embedBatch(List.of(new EmbeddingProvider.Image(bytes("x"), "image/jpeg"),
                new EmbeddingProvider.Image(bytes("y"), "image/jpeg")));
        assertThat(v.get(1)).containsExactly(fake.embed(bytes("y"), "image/jpeg"));
        assertThat(fake.model()).isEqualTo("fake:sha256-64");
    }

    @Test
    void anEmptyImageIsRefused() {
        assertThatThrownBy(() -> fake.embed(new byte[0], "image/jpeg")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theVectorLiteralIsPgvectorText() {
        assertThat(EmbeddingStore.literal(new float[] {0.5f, -1f})).isEqualTo("[0.5,-1.0]");
        assertThatThrownBy(() -> EmbeddingStore.literal(new float[] {Float.NaN})).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static double dot(float[] a, float[] b) {
        double d = 0;
        for (int i = 0; i < a.length; i++) {
            d += a[i] * b[i];
        }
        return d;
    }
}
