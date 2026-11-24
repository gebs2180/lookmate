package com.lookmate.app.consult;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.springframework.core.io.ClassPathResource;

/**
 * The checked-in seed (tools/consult/sheet_to_ruleset.py from docs/research/service-finder-template.xlsx) loads, and
 * every test case on the sheet's Tests tab gives its expected outcome, as it does in Excel.
 */
class SeedRulesetTest {

    static Ruleset seed() throws IOException {
        return RulesetJson.read(new ClassPathResource(ConsultSeed.SEED).getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void theSeedPassesEveryCheck() throws IOException {
        Ruleset r = seed();
        assertThat(RulesetValidator.problems(r)).isEmpty();
        assertThat(r.credit()).as("no credit until the partner's own version loads").isNull();
        assertThat(r.tests()).hasSize(28);
        assertThat(r.question("reaction").orElseThrow().stored()).isFalse();
    }

    @TestFactory
    Stream<DynamicTest> everySheetTestCasePasses() throws IOException {
        Ruleset r = seed();
        return r.tests().stream().map(t -> DynamicTest.dynamicTest(t.name(), () ->
                assertThat(r.evaluate(Ruleset.answers(t.answers())).orElseThrow().outcome().id())
                        .isEqualTo(t.expected())));
    }
}
