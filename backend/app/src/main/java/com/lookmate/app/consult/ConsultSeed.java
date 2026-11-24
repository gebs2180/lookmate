package com.lookmate.app.consult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Loads the checked-in example ruleset as version 1 when no ruleset exists yet (docs/modules/consult.md). Later
 * versions come from the admin upload; the seed never replaces one. A seed that fails its checks stops startup.
 */
@Component
class ConsultSeed implements ApplicationRunner {

    /** Written by tools/consult/sheet_to_ruleset.py from docs/research/service-finder-template.xlsx. */
    static final String SEED = "consult/ruleset-seed.json";

    private static final Logger log = LoggerFactory.getLogger(ConsultSeed.class);

    private final ConsultRulesets rulesets;

    ConsultSeed(ConsultRulesets rulesets) {
        this.rulesets = rulesets;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (rulesets.latest(ConsultRulesets.DEFAULT_KEY).isPresent()) {
            return;
        }
        Ruleset seed;
        try {
            seed = RulesetValidator.check(RulesetJson.read(new ClassPathResource(SEED).getContentAsString(StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        try {
            var v = rulesets.publish(ConsultRulesets.DEFAULT_KEY, seed, "SEED", null, Instant.now());
            log.info("Loaded the service finder's example ruleset as version {}", v.version());
        } catch (DataIntegrityViolationException e) {
            log.info("Another instance loaded the service finder's ruleset first");
        }
    }
}
