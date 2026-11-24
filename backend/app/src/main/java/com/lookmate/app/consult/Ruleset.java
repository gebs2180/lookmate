package com.lookmate.app.consult;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One version of a service-finder ruleset: the JSON document {@code tools/consult/sheet_to_ruleset.py} writes from the
 * partner's spreadsheet (docs/modules/consult.md). Pure data and pure logic, no Spring: rules are checked in order, the
 * first whose conditions all hold wins, and a rule with no conditions is the catch-all. Check it with
 * {@link RulesetValidator} before use.
 *
 * <p>Answers are identified by their text, exactly as the client sees it (the sheet's own identity). A condition holds
 * when the client picked that answer to that question; an unanswered question fails every condition on it.
 *
 * @param format    the document format, {@link #FORMAT}
 * @param name      the finder's title
 * @param credit    who wrote the rules, shown to clients; null for Combpatible's example answers (decision 2: the
 *                  partner is credited only once their own version is loaded)
 */
record Ruleset(int format, String name, String credit, List<Question> questions, List<Outcome> outcomes,
               List<Rule> rules, List<TestCase> tests) {

    static final int FORMAT = 1;

    enum Kind { BOOK, CONSULTATION }

    /**
     * @param multiple clients may pick several answers
     * @param help     the stylist's help text or photo idea (the example sheet's are notes to us, not client copy)
     * @param stored   false for an answer Combpatible must never keep (the colour-reaction question): it only routes
     */
    record Question(String id, String text, boolean multiple, String help, boolean stored, List<String> answers) {
    }

    /**
     * @param serviceType the closest Combpatible {@code ServiceType} code, for the hand-off to find-my-stylist
     * @param bookingUrl  the service's https booking link, or null when the stylist hasn't given one
     */
    record Outcome(String id, Kind kind, String serviceName, String serviceType, BigDecimal hours,
                   BigDecimal fromPrice, String bookingUrl, String whatToExpect) {
    }

    record Condition(String question, String answer) {
    }

    /**
     * @param why  shown to the client (optional); for a consultation, the reason
     * @param note only the stylist sees it; never served
     */
    record Rule(List<Condition> when, String outcome, String why, String note) {
        boolean holds(Map<String, Set<String>> answers) {
            for (Condition c : when) {
                Set<String> picked = answers.get(c.question());
                if (picked == null || !picked.contains(c.answer())) {
                    return false;
                }
            }
            return true;
        }
    }

    record TestCase(String name, Map<String, List<String>> answers, String expected) {
    }

    /** The winning rule (0-based position) and its outcome. */
    record Result(int ruleIndex, Rule rule, Outcome outcome) {
    }

    /** The first rule whose conditions all hold, or empty when none does (a validated ruleset always has one). */
    Optional<Result> evaluate(Map<String, Set<String>> answers) {
        for (int i = 0; i < rules.size(); i++) {
            Rule rule = rules.get(i);
            if (rule.holds(answers)) {
                int at = i;
                return outcome(rule.outcome()).map(o -> new Result(at, rule, o));
            }
        }
        return Optional.empty();
    }

    Optional<Question> question(String id) {
        return questions.stream().filter(q -> q.id().equals(id)).findFirst();
    }

    Optional<Outcome> outcome(String id) {
        return outcomes.stream().filter(o -> o.id().equals(id)).findFirst();
    }

    /** A test case's answers as the engine takes them. */
    static Map<String, Set<String>> answers(Map<String, List<String>> lists) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        lists.forEach((q, a) -> out.put(q, new LinkedHashSet<>(a)));
        return out;
    }

    /** The answers worth keeping: those to questions marked stored. */
    Map<String, List<String>> storable(Map<String, Set<String>> answers) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        answers.forEach((q, a) -> {
            if (question(q).map(Question::stored).orElse(false)) {
                out.put(q, new ArrayList<>(a));
            }
        });
        return out;
    }
}
