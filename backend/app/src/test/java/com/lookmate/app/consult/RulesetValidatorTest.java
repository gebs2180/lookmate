package com.lookmate.app.consult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lookmate.app.stylists.ServiceType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class RulesetValidatorTest {

    private static Ruleset.Question q(String id, boolean multiple, String... answers) {
        return new Ruleset.Question(id, "Question " + id + "?", multiple, null, true, List.of(answers));
    }

    private static Ruleset.Outcome book(String id) {
        return new Ruleset.Outcome(id, Ruleset.Kind.BOOK, "Service " + id, "COLOR", BigDecimal.ONE, BigDecimal.TEN,
                null, "What happens");
    }

    private static Ruleset.Outcome consult(String id) {
        return new Ruleset.Outcome(id, Ruleset.Kind.CONSULTATION, "Consultation", "COLOR", null, BigDecimal.ZERO,
                null, null);
    }

    private static Ruleset.Rule rule(String outcome, String... conditions) {
        List<Ruleset.Condition> when = Arrays.stream(conditions).map(c -> c.split(": ", 2))
                .map(c -> new Ruleset.Condition(c[0], c[1])).toList();
        return new Ruleset.Rule(when, outcome, "why " + outcome, null);
    }

    private static Ruleset.TestCase test(String expected, String... answers) {
        Map<String, List<String>> map = Arrays.stream(answers).map(a -> a.split(": ", 2))
                .collect(Collectors.toMap(a -> a[0], a -> List.of(a[1].split("; "))));
        return new Ruleset.TestCase("expects " + expected + " " + Arrays.toString(answers), map, expected);
    }

    /** A small valid ruleset: two hair types, a reaction question, and a catch-all. */
    private static Ruleset small(List<Ruleset.Rule> rules, List<Ruleset.TestCase> tests) {
        return new Ruleset(1, "Finder", null,
                List.of(q("hair_type", false, "Straight", "Curly"), q("goal", false, "Brighter", "Darker"),
                        q("history", true, "Box dye", "Bleach"),
                        new Ruleset.Question("reaction", "Reaction?", false, null, false, List.of("Yes", "No"))),
                List.of(book("GLOSS"), book("ALL_OVER"), consult("CONSULT")), rules, tests);
    }

    private static List<Ruleset.Rule> standardRules() {
        return List.of(
                rule("CONSULT", "reaction: Yes"),
                rule("CONSULT", "history: Box dye", "goal: Brighter"),
                rule("ALL_OVER", "goal: Darker"),
                rule("GLOSS", "goal: Brighter"),
                rule("CONSULT"));
    }

    private static List<Ruleset.TestCase> standardTests() {
        return List.of(
                test("CONSULT", "hair_type: Straight", "reaction: Yes", "goal: Darker"),
                test("CONSULT", "hair_type: Curly", "history: Bleach; Box dye", "goal: Brighter"),
                test("ALL_OVER", "hair_type: Curly", "goal: Darker"),
                test("GLOSS", "hair_type: Straight", "goal: Brighter", "history: Bleach"),
                test("CONSULT", "hair_type: Straight"));
    }

    @Test
    void aValidRulesetPasses() {
        assertThat(RulesetValidator.problems(small(standardRules(), standardTests()))).isEmpty();
    }

    @Test
    void theFirstMatchingRuleWinsAndEveryConditionMustHold() {
        Ruleset r = small(standardRules(), standardTests());
        var answers = Map.of("goal", Set.of("Brighter"), "history", Set.of("Box dye", "Bleach"));
        assertThat(r.evaluate(answers).orElseThrow().ruleIndex()).isEqualTo(1);
        // Box dye alone doesn't make rule 2 fit: it also needs Brighter.
        assertThat(r.evaluate(Map.of("history", Set.of("Box dye"))).orElseThrow().ruleIndex()).isEqualTo(4);
        assertThat(r.evaluate(Map.of("goal", Set.of("Brighter"))).orElseThrow().outcome().id()).isEqualTo("GLOSS");
    }

    @Test
    void aRuleSetWithoutACatchAllIsCheckedForGaps() {
        List<Ruleset.Rule> rules = new ArrayList<>(standardRules());
        rules.removeLast();
        List<Ruleset.TestCase> tests = standardTests().subList(0, 4);
        assertThat(RulesetValidator.problems(small(rules, tests)))
                .singleElement().asString().startsWith("Some answers reach no outcome");
    }

    @Test
    void rulesThatCoverEveryCombinationNeedNoCatchAll() {
        List<Ruleset.Rule> rules = List.of(rule("ALL_OVER", "goal: Darker"), rule("GLOSS", "goal: Brighter"),
                rule("CONSULT", "hair_type: Straight"), rule("CONSULT", "hair_type: Curly"));
        // Skipping goal and hair_type both reaches nothing.
        assertThat(RulesetValidator.problems(small(rules, List.of(test("GLOSS", "hair_type: Straight", "goal: Brighter"),
                test("ALL_OVER", "hair_type: Curly", "goal: Darker"))))).singleElement().asString()
                .contains("hair_type: (none");
    }

    @Test
    void rulesMustNameRealQuestionsAnswersAndOutcomes() {
        List<Ruleset.Rule> rules = new ArrayList<>(standardRules());
        rules.add(0, rule("GLOSS", "colour: Brighter"));
        rules.add(0, rule("GLOSS", "goal: Lighter"));
        rules.add(0, rule("NOPE", "goal: Brighter"));
        assertThat(RulesetValidator.problems(small(rules, standardTests()))).containsExactly(
                "Rule 1: outcome 'NOPE' isn't in Outcomes.",
                "Rule 2: 'Lighter' isn't an answer to goal.",
                "Rule 3: question 'colour' isn't in Questions.");
    }

    @Test
    void everyTestMustPass() {
        List<Ruleset.TestCase> tests = new ArrayList<>(standardTests());
        tests.add(test("GLOSS", "hair_type: Curly", "goal: Darker"));
        assertThat(RulesetValidator.problems(small(standardRules(), tests)))
                .singleElement().asString().contains("expected GLOSS, got ALL_OVER (rule 3)");
    }

    @Test
    void testsMustUseRealAnswersAndOneAnswerForSingleChoice() {
        List<Ruleset.TestCase> tests = new ArrayList<>(standardTests());
        tests.add(test("GLOSS", "hair_type: Curly", "goal: Darker; Brighter"));
        tests.add(test("GLOSS", "hair_type: Wavy"));
        assertThat(RulesetValidator.problems(small(standardRules(), tests))).satisfiesExactly(
                p -> assertThat(p).endsWith("goal takes one answer."),
                p -> assertThat(p).endsWith("'Wavy' isn't an answer to hair_type."));
    }

    @Test
    void everyHairTypeNeedsATest() {
        List<Ruleset.TestCase> tests = standardTests().stream()
                .filter(t -> !t.answers().get("hair_type").contains("Curly")).toList();
        assertThat(RulesetValidator.problems(small(standardRules(), tests)))
                .containsExactly("No test covers hair_type: Curly. Write at least one test for each hair type.");
    }

    @Test
    void theReactionQuestionOnlyLeadsToAConsultation() {
        List<Ruleset.Rule> rules = new ArrayList<>(standardRules());
        rules.set(0, rule("GLOSS", "reaction: No", "goal: Brighter"));
        List<Ruleset.TestCase> tests = standardTests().subList(1, 5);
        assertThat(RulesetValidator.problems(small(rules, tests)))
                .containsExactly("Rule 1: answers to reaction may only lead to a consultation.");
    }

    @Test
    void outcomesAreChecked() {
        Ruleset base = small(standardRules(), standardTests());
        List<Ruleset.Outcome> outcomes = new ArrayList<>(base.outcomes());
        outcomes.set(0, new Ruleset.Outcome("GLOSS", Ruleset.Kind.BOOK, "Gloss", "GLITTER", BigDecimal.ZERO,
                new BigDecimal("-1"), "http://example.com", null));
        Ruleset r = new Ruleset(1, "Finder", null, base.questions(), outcomes, base.rules(), base.tests());
        assertThat(RulesetValidator.problems(r)).satisfiesExactly(
                p -> assertThat(p).startsWith("Outcome GLOSS: Combpatible service 'GLITTER' isn't one of"),
                p -> assertThat(p).isEqualTo("Outcome GLOSS: time must be more than 0 hours."),
                p -> assertThat(p).isEqualTo("Outcome GLOSS: the from-price can't be negative."),
                p -> assertThat(p).isEqualTo("Outcome GLOSS: the booking link must be a full https:// address, or empty."));
    }

    @Test
    void checkThrowsWithEveryProblem() {
        Ruleset r = new Ruleset(2, " ", null, List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> RulesetValidator.check(r)).isInstanceOf(RulesetValidator.Invalid.class)
                .satisfies(e -> assertThat(((RulesetValidator.Invalid) e).problems()).hasSize(5));
    }

    @Test
    void notJsonIsInvalid() {
        assertThatThrownBy(() -> RulesetJson.read("{\"format\": ")).isInstanceOf(RulesetValidator.Invalid.class);
    }

    @Test
    void theServiceTypeCopyMatchesTheStylistsModule() {
        assertThat(RulesetValidator.SERVICE_TYPES)
                .isEqualTo(Arrays.stream(ServiceType.values()).map(Enum::name).collect(Collectors.toSet()));
    }

    @Test
    void storableDropsTheReactionAnswer() {
        Ruleset r = small(standardRules(), standardTests());
        assertThat(r.storable(Map.of("reaction", Set.of("Yes"), "goal", Set.of("Darker"))))
                .isEqualTo(Map.of("goal", List.of("Darker")));
    }
}
