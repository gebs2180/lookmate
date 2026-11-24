package com.lookmate.app.consult;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Checks a ruleset before it is loaded or published (docs/modules/consult.md). A ruleset that fails is never used:
 * every rule names a real question, answer and outcome; every test case gives its expected outcome; no combination of
 * answers (skipping any question included) reaches no outcome; the colour-reaction question only routes to a
 * consultation; and there's a test for every hair type (the fairness commitment). Pure Java.
 */
final class RulesetValidator {

    /**
     * Combpatible's {@code ServiceType} codes (the V2 check constraints). A copy, because consult depends on no feature
     * module; {@code RulesetValidatorTest} keeps it equal to the stylists module's enum.
     */
    static final Set<String> SERVICE_TYPES = Set.of("CUT", "COLOR", "BALAYAGE", "HIGHLIGHTS", "EXTENSIONS", "BRAIDS",
            "LOCS", "SILK_PRESS", "PERM", "TREATMENT", "STYLING", "OTHER");

    /** The question whose answers each need a test (CLAUDE.md: fairness across hair types is measured, not assumed). */
    static final String HAIR_TYPE = "hair_type";

    /** Above this many answer combinations without a catch-all rule, the check gives up and asks for one. */
    static final long MAX_COMBINATIONS = 2_000_000;

    private static final Pattern QUESTION_ID = Pattern.compile("[a-z][a-z0-9_]{0,39}");
    private static final Pattern OUTCOME_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,59}");

    /** A ruleset that failed its checks; {@link #problems()} says why, one line each. */
    static final class Invalid extends RuntimeException {
        private final List<String> problems;

        Invalid(List<String> problems) {
            super("The ruleset has " + problems.size() + " problem(s): " + String.join("; ", problems));
            this.problems = List.copyOf(problems);
        }

        List<String> problems() {
            return problems;
        }
    }

    private RulesetValidator() {
    }

    /** Throws {@link Invalid} unless the ruleset passes every check. */
    static Ruleset check(Ruleset r) {
        List<String> problems = problems(r);
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        return r;
    }

    /** Every problem found, or an empty list. */
    static List<String> problems(Ruleset r) {
        List<String> p = new ArrayList<>();
        if (r == null) {
            return List.of("The ruleset is empty.");
        }
        if (r.format() != Ruleset.FORMAT) {
            p.add("format must be " + Ruleset.FORMAT + ", not " + r.format() + ".");
        }
        if (blank(r.name())) {
            p.add("The ruleset needs a name.");
        }
        if (r.credit() != null && (r.credit().isBlank() || r.credit().length() > 120)) {
            p.add("credit must be null or 1 to 120 characters.");
        }
        if (r.questions() == null || r.questions().isEmpty()) {
            p.add("There are no questions.");
        }
        if (r.outcomes() == null || r.outcomes().isEmpty()) {
            p.add("There are no outcomes.");
        }
        if (r.rules() == null || r.rules().isEmpty()) {
            p.add("There are no rules.");
        }
        if (!p.isEmpty()) {
            return p;
        }
        boolean shapeOk = questions(r, p) & outcomes(r, p) & rules(r, p);
        if (r.tests() == null || r.tests().isEmpty()) {
            p.add("There are no test cases.");
        } else if (shapeOk) {
            tests(r, p);
        }
        if (shapeOk) {
            coverage(r, p);
        }
        return p;
    }

    private static boolean questions(Ruleset r, List<String> p) {
        int before = p.size();
        Set<String> ids = new HashSet<>();
        for (Ruleset.Question q : r.questions()) {
            if (q == null || q.id() == null || !QUESTION_ID.matcher(q.id()).matches()) {
                p.add("Question ID '" + (q == null ? null : q.id()) + "' must be lower-case letters, digits and _ (like hair_type).");
                continue;
            }
            if (!ids.add(q.id())) {
                p.add("Question " + q.id() + " appears twice.");
            }
            if (blank(q.text())) {
                p.add("Question " + q.id() + " has no text.");
            }
            if (q.answers() == null || q.answers().isEmpty()) {
                p.add("Question " + q.id() + " has no answers.");
                continue;
            }
            Set<String> answers = new HashSet<>();
            for (String a : q.answers()) {
                if (blank(a)) {
                    p.add("Question " + q.id() + " has an empty answer.");
                } else if (a.contains(";")) {
                    p.add("Question " + q.id() + ": answer '" + a + "' contains a semicolon.");
                } else if (!answers.add(a)) {
                    p.add("Question " + q.id() + ": answer '" + a + "' appears twice.");
                }
            }
        }
        return p.size() == before;
    }

    private static boolean outcomes(Ruleset r, List<String> p) {
        int before = p.size();
        Set<String> ids = new HashSet<>();
        for (Ruleset.Outcome o : r.outcomes()) {
            if (o == null || o.id() == null || !OUTCOME_ID.matcher(o.id()).matches()) {
                p.add("Outcome ID '" + (o == null ? null : o.id()) + "' must be letters, digits and _ (like BALAYAGE_FULL).");
                continue;
            }
            String at = "Outcome " + o.id();
            if (!ids.add(o.id())) {
                p.add(at + " appears twice.");
            }
            if (o.kind() == null) {
                p.add(at + " must be Book or Consultation.");
            }
            if (blank(o.serviceName())) {
                p.add(at + " has no service name.");
            }
            if (o.serviceType() == null || !SERVICE_TYPES.contains(o.serviceType())) {
                p.add(at + ": Combpatible service '" + o.serviceType() + "' isn't one of " + String.join(", ", SERVICE_TYPES.stream().sorted().toList()) + ".");
            }
            if (o.hours() != null && o.hours().signum() <= 0) {
                p.add(at + ": time must be more than 0 hours.");
            }
            if (o.fromPrice() != null && o.fromPrice().signum() < 0) {
                p.add(at + ": the from-price can't be negative.");
            }
            if (o.bookingUrl() != null && !httpsLink(o.bookingUrl())) {
                p.add(at + ": the booking link must be a full https:// address, or empty.");
            }
        }
        return p.size() == before;
    }

    private static boolean rules(Ruleset r, List<String> p) {
        int before = p.size();
        for (int i = 0; i < r.rules().size(); i++) {
            Ruleset.Rule rule = r.rules().get(i);
            String at = "Rule " + (i + 1);
            if (rule == null || rule.when() == null) {
                p.add(at + " is empty.");
                continue;
            }
            Optional<Ruleset.Outcome> outcome = rule.outcome() == null ? Optional.empty() : r.outcome(rule.outcome());
            if (outcome.isEmpty()) {
                p.add(at + ": outcome '" + rule.outcome() + "' isn't in Outcomes.");
            }
            Map<String, String> singleChoice = new HashMap<>();
            for (Ruleset.Condition c : rule.when()) {
                Optional<Ruleset.Question> q = c == null || c.question() == null ? Optional.empty() : r.question(c.question());
                if (q.isEmpty()) {
                    p.add(at + ": question '" + (c == null ? null : c.question()) + "' isn't in Questions.");
                    continue;
                }
                if (!q.get().answers().contains(c.answer())) {
                    p.add(at + ": '" + c.answer() + "' isn't an answer to " + c.question() + ".");
                    continue;
                }
                if (!q.get().multiple()) {
                    String other = singleChoice.putIfAbsent(c.question(), c.answer());
                    if (other != null && !other.equals(c.answer())) {
                        p.add(at + " can never fit: " + c.question() + " takes one answer, and it asks for two.");
                    }
                }
                if (!q.get().stored() && outcome.isPresent() && outcome.get().kind() != Ruleset.Kind.CONSULTATION) {
                    p.add(at + ": answers to " + c.question() + " may only lead to a consultation.");
                }
            }
        }
        return p.size() == before;
    }

    private static void tests(Ruleset r, List<String> p) {
        Set<String> hairTypesTested = new HashSet<>();
        for (int i = 0; i < r.tests().size(); i++) {
            Ruleset.TestCase t = r.tests().get(i);
            String at = "Test " + (i + 1) + (t == null || blank(t.name()) ? "" : " (" + t.name() + ")");
            if (t == null || blank(t.name()) || t.answers() == null) {
                p.add(at + " needs a name and answers.");
                continue;
            }
            if (t.expected() == null || r.outcome(t.expected()).isEmpty()) {
                p.add(at + ": expected outcome '" + t.expected() + "' isn't in Outcomes.");
                continue;
            }
            Optional<String> bad = answerProblem(r, t.answers());
            if (bad.isPresent()) {
                p.add(at + ": " + bad.get());
                continue;
            }
            hairTypesTested.addAll(t.answers().getOrDefault(HAIR_TYPE, List.of()));
            Optional<Ruleset.Result> result = r.evaluate(Ruleset.answers(t.answers()));
            String got = result.map(x -> x.outcome().id() + " (rule " + (x.ruleIndex() + 1) + ")").orElse("no rule fits");
            if (result.isEmpty() || !result.get().outcome().id().equals(t.expected())) {
                p.add(at + ": expected " + t.expected() + ", got " + got + ".");
            }
        }
        r.question(HAIR_TYPE).ifPresent(q -> q.answers().stream().filter(a -> !hairTypesTested.contains(a))
                .forEach(a -> p.add("No test covers " + HAIR_TYPE + ": " + a + ". Write at least one test for each hair type.")));
    }

    /** Why a set of answers doesn't fit the questions, or empty when it does. Also checks clients' submissions. */
    static Optional<String> answerProblem(Ruleset r, Map<String, ? extends java.util.Collection<String>> answers) {
        for (Map.Entry<String, ? extends java.util.Collection<String>> e : answers.entrySet()) {
            Optional<Ruleset.Question> q = r.question(e.getKey());
            if (q.isEmpty()) {
                return Optional.of("'" + e.getKey() + "' isn't a question.");
            }
            if (e.getValue() == null) {
                return Optional.of(e.getKey() + " has no answers.");
            }
            if (!q.get().multiple() && e.getValue().size() > 1) {
                return Optional.of(e.getKey() + " takes one answer.");
            }
            for (String a : e.getValue()) {
                if (!q.get().answers().contains(a)) {
                    return Optional.of("'" + a + "' isn't an answer to " + e.getKey() + ".");
                }
            }
        }
        return Optional.empty();
    }

    /**
     * No combination of answers may reach no outcome. A rule with no conditions settles it; otherwise every
     * combination of the answers the rules mention (one or none for a single-choice question, any subset for a
     * multiple-choice one) is tried, since answers no rule mentions can't change which rule fits.
     */
    private static void coverage(Ruleset r, List<String> p) {
        if (r.rules().stream().anyMatch(rule -> rule.when().isEmpty())) {
            return;
        }
        Map<String, List<Set<String>>> choices = new LinkedHashMap<>();
        for (Ruleset.Rule rule : r.rules()) {
            for (Ruleset.Condition c : rule.when()) {
                choices.computeIfAbsent(c.question(), k -> new ArrayList<>());
            }
        }
        long combinations = 1;
        for (Map.Entry<String, List<Set<String>>> e : choices.entrySet()) {
            Ruleset.Question q = r.question(e.getKey()).orElseThrow();
            List<String> mentioned = r.rules().stream().flatMap(rule -> rule.when().stream())
                    .filter(c -> c.question().equals(q.id())).map(Ruleset.Condition::answer).distinct().toList();
            List<Set<String>> options = e.getValue();
            options.add(Set.of());
            if (q.multiple()) {
                if (mentioned.size() > 20) {
                    p.add("Too many answer combinations to check: add a catch-all rule with no conditions at the bottom.");
                    return;
                }
                for (int mask = 1; mask < (1 << mentioned.size()); mask++) {
                    Set<String> s = new LinkedHashSet<>();
                    for (int b = 0; b < mentioned.size(); b++) {
                        if ((mask & (1 << b)) != 0) {
                            s.add(mentioned.get(b));
                        }
                    }
                    options.add(s);
                }
            } else {
                mentioned.forEach(a -> options.add(Set.of(a)));
            }
            combinations *= options.size();
            if (combinations > MAX_COMBINATIONS) {
                p.add("Too many answer combinations to check: add a catch-all rule with no conditions at the bottom.");
                return;
            }
        }
        List<String> order = new ArrayList<>(choices.keySet());
        Map<String, Set<String>> answers = new HashMap<>();
        Optional<Map<String, Set<String>>> gap = search(r, order, 0, choices, answers);
        gap.ifPresent(g -> p.add("Some answers reach no outcome, for example " + describe(order, g)
                + ". Add a catch-all rule with no conditions at the bottom."));
    }

    private static Optional<Map<String, Set<String>>> search(Ruleset r, List<String> order, int at,
                                                             Map<String, List<Set<String>>> choices, Map<String, Set<String>> answers) {
        if (at == order.size()) {
            return r.evaluate(answers).isPresent() ? Optional.empty() : Optional.of(new HashMap<>(answers));
        }
        String q = order.get(at);
        for (Set<String> option : choices.get(q)) {
            if (option.isEmpty()) {
                answers.remove(q);
            } else {
                answers.put(q, option);
            }
            Optional<Map<String, Set<String>>> gap = search(r, order, at + 1, choices, answers);
            if (gap.isPresent()) {
                return gap;
            }
        }
        answers.remove(q);
        return Optional.empty();
    }

    private static String describe(List<String> order, Map<String, Set<String>> answers) {
        List<String> parts = new ArrayList<>();
        for (String q : order) {
            Set<String> a = answers.get(q);
            parts.add(q + ": " + (a == null || a.isEmpty() ? "(none of the answers the rules use)" : String.join("; ", a)));
        }
        return String.join(", ", parts);
    }

    static boolean httpsLink(String url) {
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank()
                    && url.length() <= 2000;
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
