package com.lookmate.app.matching;

import static com.lookmate.app.matching.HairType.COILY;
import static com.lookmate.app.matching.HairType.CURLY;
import static com.lookmate.app.matching.HairType.STRAIGHT;
import static com.lookmate.app.matching.HairType.WAVY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HairCompatibilityTest {

    private final HairCompatibility hair = new HairCompatibility(0.5);

    @Test
    void theSameTypeCountsFully() {
        for (HairType t : HairType.values()) {
            assertThat(hair.weight(t, t)).isEqualTo(1.0);
        }
    }

    @Test
    void neighbouringTypesCountAtTheAdjacentWeightBothWays() {
        assertThat(hair.weight(WAVY, CURLY)).isEqualTo(0.5);
        assertThat(hair.weight(CURLY, WAVY)).isEqualTo(0.5);
        assertThat(hair.weight(STRAIGHT, WAVY)).isEqualTo(0.5);
        assertThat(hair.weight(COILY, CURLY)).isEqualTo(0.5);
    }

    @Test
    void typesTwoOrMoreApartDoNotCount() {
        assertThat(hair.weight(COILY, WAVY)).isZero();
        assertThat(hair.weight(COILY, STRAIGHT)).isZero();
        assertThat(hair.weight(STRAIGHT, CURLY)).isZero();
    }

    @Test
    void anUntaggedPhotoDoesNotCountButAClientWithoutATypeMatchesEveryType() {
        assertThat(hair.weight(WAVY, null)).isZero();
        assertThat(hair.weight(null, COILY)).isEqualTo(1.0);
        assertThat(hair.compatible(null)).containsExactly(HairType.values());
    }

    @Test
    void compatibleTypesAreTheOnesWithWeight() {
        assertThat(hair.compatible(COILY)).containsExactly(CURLY, COILY);
        assertThat(hair.compatible(WAVY)).containsExactly(STRAIGHT, WAVY, CURLY);
        assertThat(new HairCompatibility(0).compatible(WAVY)).containsExactly(WAVY);
    }

    @Test
    void theAdjacentWeightIsBetweenZeroAndOne() {
        assertThatThrownBy(() -> new HairCompatibility(1.5)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new HairCompatibility(-0.1)).isInstanceOf(IllegalArgumentException.class);
    }
}
