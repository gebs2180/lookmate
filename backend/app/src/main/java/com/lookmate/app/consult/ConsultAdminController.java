package com.lookmate.app.consult;

import com.lookmate.app.api.ApiException;
import com.lookmate.app.identity.CurrentUser;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin's ruleset versions (ADMIN only, by {@code /api/admin/**}). An upload is the converter's JSON
 * (tools/consult/sheet_to_ruleset.py); it is checked like the seed and becomes the next version, live at once for both
 * links, or is refused with every problem listed. Published versions never change.
 */
@RestController
class ConsultAdminController {

    static final String BASE = "/api/admin/consult/rulesets";

    enum Code { RULESET_INVALID, NOT_FOUND }

    /** A published version; {@code current} is the one clients get. */
    record RulesetVersion(UUID id, int version, String name, String credit, String source, Instant publishedAt,
                          int questions, int outcomes, int rules, int tests, boolean current) {
    }

    /** 422 with {@code problems}, one line each. */
    static final class Invalid extends ApiException {
        Invalid(List<String> problems) {
            super(HttpStatus.UNPROCESSABLE_CONTENT, Code.RULESET_INVALID,
                    "The ruleset wasn't published: it has " + problems.size() + " problem(s).");
            getBody().setProperty("problems", problems);
        }
    }

    private final ConsultRulesets rulesets;
    private final CurrentUser currentUser;
    private final Clock clock;

    ConsultAdminController(ConsultRulesets rulesets, CurrentUser currentUser, Optional<Clock> clock) {
        this.rulesets = rulesets;
        this.currentUser = currentUser;
        this.clock = clock.orElse(Clock.systemUTC());
    }

    @GetMapping(BASE)
    @Transactional(readOnly = true)
    List<RulesetVersion> list() {
        List<ConsultRulesets.Version> versions = rulesets.list(ConsultRulesets.DEFAULT_KEY);
        return versions.stream().map(v -> view(v, v == versions.getFirst())).toList();
    }

    /** One version's whole document, as uploaded (to compare, or to download and edit). */
    @GetMapping(BASE + "/{version}")
    @Transactional(readOnly = true)
    Ruleset get(@PathVariable int version) {
        return rulesets.list(ConsultRulesets.DEFAULT_KEY).stream().filter(v -> v.version() == version).findFirst()
                .map(v -> rulesets.ruleset(v.id()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, Code.NOT_FOUND, "There's no version " + version + "."));
    }

    @PostMapping(BASE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    RulesetVersion upload(@RequestBody Ruleset ruleset) {
        List<String> problems = RulesetValidator.problems(ruleset);
        if (!problems.isEmpty()) {
            throw new Invalid(problems);
        }
        UUID by = currentUser.id();
        return view(rulesets.publish(ConsultRulesets.DEFAULT_KEY, ruleset, "UPLOAD", by, clock.instant()), true);
    }

    private RulesetVersion view(ConsultRulesets.Version v, boolean current) {
        Ruleset r = rulesets.ruleset(v.id());
        return new RulesetVersion(v.id(), v.version(), r.name(), v.credit(), v.source(), v.publishedAt(),
                r.questions().size(), r.outcomes().size(), r.rules().size(), r.tests().size(), current);
    }
}
