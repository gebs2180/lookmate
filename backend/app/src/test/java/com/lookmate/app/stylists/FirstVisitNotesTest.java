package com.lookmate.app.stylists;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The generic first-visit notes: the shipped file loads, the pick order, the cap of four, and keys are checked. */
class FirstVisitNotesTest {

    private final FirstVisitNotes shipped = new FirstVisitNotes();

    @Test
    void theShippedFileLoadsAndPicksMostSpecificFirstAtMostFour() {
        assertThat(shipped.pick(ServiceType.CUT, HairType.CURLY)).extracting(FirstVisitNote::key)
                .containsExactly("CUT+CURLY", "CUT", "CURLY", "any.photos");
        // No note for STRAIGHT: the service, then the notes for any visit.
        assertThat(shipped.pick(ServiceType.BALAYAGE, HairType.STRAIGHT)).extracting(FirstVisitNote::key)
                .containsExactly("BALAYAGE", "any.photos", "any.routine");
        assertThat(shipped.pick(ServiceType.OTHER, null)).extracting(FirstVisitNote::key)
                .containsExactly("any.photos", "any.routine");
        assertThat(shipped.pick(null, HairType.COILY)).extracting(FirstVisitNote::key)
                .containsExactly("COILY", "any.photos", "any.routine");
        assertThat(shipped.pick(ServiceType.COLOR, null).getFirst().text()).startsWith("Have your colour history ready");
    }

    @Test
    void everyPairingHasANoteAndNoneIsLong() {
        for (ServiceType s : ServiceType.values()) {
            for (HairType h : HairType.values()) {
                assertThat(shipped.pick(s, h)).isNotEmpty().hasSizeLessThanOrEqualTo(FirstVisitNotes.MAX)
                        .allSatisfy(n -> assertThat(n.text()).hasSizeLessThan(300));
            }
        }
    }

    @Test
    void unknownKeysAndEmptyNotesFailTheStart() {
        assertThatThrownBy(() -> new FirstVisitNotes(Map.of("CUTS", "x"))).hasMessageContaining("Unknown first-visit note key CUTS");
        assertThatThrownBy(() -> new FirstVisitNotes(Map.of("CURLY+CUT", "x"))).hasMessageContaining("CURLY+CUT");
        assertThatThrownBy(() -> new FirstVisitNotes(Map.of("any.", "x"))).hasMessageContaining("any.");
        Map<String, String> blank = new LinkedHashMap<>();
        blank.put("CUT", " ");
        assertThatThrownBy(() -> new FirstVisitNotes(blank)).hasMessageContaining("CUT is empty");
    }
}
